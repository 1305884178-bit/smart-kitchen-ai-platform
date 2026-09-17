import json
import asyncio
import contextlib
import logging
from app.agents.cs_agent import cs_agent
from app.config import settings
from app.services.semantic_cache_service import semantic_cache
from app.services.query_rewrite import rewrite_query
from app.services.retrieval_context import current_retrieval_query
from app.services.chat_cancellation import is_cancelled
from langchain_core.messages import HumanMessage, AIMessage

logger = logging.getLogger(__name__)

# 回调 Java 查 MySQL 实时数据的工具：其回答含库存/配料快照，写入缓存会向其他用户散发过期数据
DYNAMIC_TOOLS = {"check_dish_inventory", "get_dish_ingredients"}


def _build_agent_messages(message: str, history: list[dict], max_messages: int):
    """组装送入 ReAct Agent 的消息：只取最近 N 条（含当前），全量历史禁止。"""
    msgs = []
    for m in history[-(max_messages - 1):]:
        role = m.get("role")
        content = (m.get("content") or "").strip()
        if not content:
            continue
        if role == "user":
            msgs.append(HumanMessage(content=content))
        elif role == "assistant":
            msgs.append(AIMessage(content=content))
    msgs.append(HumanMessage(content=message))
    return msgs


class ChatService:
    @staticmethod
    async def generate_chat_stream(message: str, history: list[dict] | None = None,
                                   retrieval_query: str | None = None,
                                   question_vector: list[float] | None = None,
                                   cache_question: str | None = None,
                                   request_id: str | None = None,
                                   cancel_event: asyncio.Event | None = None):
        # 用来收集完整回复以便缓存
        full_response = ""
        dynamic_tool_invoked = False

        agent_messages = _build_agent_messages(
            message, history or [], settings.chat_history_max_messages
        )

        # 检索用改写问句写入上下文，供 search_dish_by_preference 使用（只影响检索，不改用户原话）
        token = current_retrieval_query.set(retrieval_query or message)
        try:
            # 异步流式调用 Agent
            # 注意：astream_events v1 已被弃用，建议后续升至 v2，此处先维持功能不变
            events = cs_agent.astream_events(
                {"messages": agent_messages},
                config={"configurable": {"request_id": request_id}},
                version="v1"
            ).__aiter__()
            while True:
                if is_cancelled(request_id) or (cancel_event and cancel_event.is_set()):
                    logger.info("[Chat] request cancelled: %s", request_id)
                    return

                event_task = asyncio.ensure_future(events.__anext__())
                cancel_task = asyncio.create_task(cancel_event.wait()) if cancel_event else None
                waiting = {event_task}
                if cancel_task:
                    waiting.add(cancel_task)
                try:
                    done, _ = await asyncio.wait(waiting, return_when=asyncio.FIRST_COMPLETED)
                except BaseException:
                    # SSE 客户端断开时 StreamingResponse 会取消本协程；同时取消正在
                    # 等待的 Agent 事件，避免模型/HTTP 工具脱离客户端继续消耗资源。
                    for task in waiting:
                        task.cancel()
                    for task in waiting:
                        with contextlib.suppress(asyncio.CancelledError, StopAsyncIteration):
                            await task
                    raise

                if cancel_task and cancel_task in done:
                    event_task.cancel()
                    with contextlib.suppress(asyncio.CancelledError):
                        await event_task
                    return
                if cancel_task:
                    cancel_task.cancel()
                    with contextlib.suppress(asyncio.CancelledError):
                        await cancel_task
                try:
                    event = event_task.result()
                except StopAsyncIteration:
                    break

                # 每个 Agent 事件边界检查。工具也会从 config 读取 request_id 再检查一次。
                if is_cancelled(request_id) or (cancel_event and cancel_event.is_set()):
                    logger.info("[Chat] request cancelled: %s", request_id)
                    return
                kind = event["event"]
                if kind == "on_chat_model_stream":
                    chunk = event["data"]["chunk"]
                    if chunk.content:
                        full_response += chunk.content
                        # 按照 SSE 格式返回数据
                        yield f"data: {json.dumps({'content': chunk.content})}\n\n"
                elif kind == "on_tool_start" and event.get("name") in DYNAMIC_TOOLS:
                    dynamic_tool_invoked = True
        finally:
            current_retrieval_query.reset(token)

        # 只缓存非动态回答：纯知识问答与 RAG 推荐（search_dish_by_preference 查的是知识库，
        # 知识库更新会通过 kb_version 指纹使旧缓存失效）均可缓存；含实时工具结果的不缓存。
        # 缓存 key 一律使用改写后的完整问句（cache_question），禁止用含指代的用户原句。
        if (full_response and not dynamic_tool_invoked and not is_cancelled(request_id)
                and not (cancel_event and cancel_event.is_set())):
            semantic_cache.store(cache_question or message, full_response, question_vector=question_vector)

        if not is_cancelled(request_id) and not (cancel_event and cancel_event.is_set()):
            yield "data: [DONE]\n\n"

    @staticmethod
    async def generate_cached_stream(cached_response: str, request_id: str | None = None,
                                     cancel_event: asyncio.Event | None = None):
        # 如果有缓存，稍微切分一下模拟流式
        chunk_size = 10
        for i in range(0, len(cached_response), chunk_size):
            if is_cancelled(request_id) or (cancel_event and cancel_event.is_set()):
                return
            chunk = cached_response[i:i+chunk_size]
            yield f"data: {json.dumps({'content': chunk})}\n\n"
            await asyncio.sleep(0.01)
        if not is_cancelled(request_id) and not (cancel_event and cancel_event.is_set()):
            yield "data: [DONE]\n\n"

    @classmethod
    def get_chat_response_generator(cls, message: str, history: list[dict] | None = None,
                                    conversation_id: str | None = None,
                                    request_id: str | None = None,
                                    cancel_event: asyncio.Event | None = None):
        """
        核心业务逻辑：语义缓存命中则返回缓存流，否则调用大模型流。

        检索与语义缓存统一使用改写后的完整问句（rewrite_query）：
        「这个辣不辣」会先补全为「水煮鱼辣不辣」再 lookup/store，
        避免含指代原句永远 miss 或串答；无历史时改写结果等于原句，行为与单轮一致。
        未命中时 lookup 已算好的问题向量随调用传递，写缓存时无需重复 embedding。
        """
        history = history or []
        rewritten = rewrite_query(message, history)
        if rewritten != message:
            logger.info(f"[Chat] 检索/缓存使用改写句: 「{message}」->「{rewritten}」"
                        f"(conversation={conversation_id or '-'})")

        cached_response, question_vector = semantic_cache.lookup(rewritten)

        if cached_response:
            return cls.generate_cached_stream(
                cached_response, request_id=request_id, cancel_event=cancel_event
            )

        return cls.generate_chat_stream(
            message,
            history=history,
            retrieval_query=rewritten,
            question_vector=question_vector,
            cache_question=rewritten,
            request_id=request_id,
            cancel_event=cancel_event,
        )
