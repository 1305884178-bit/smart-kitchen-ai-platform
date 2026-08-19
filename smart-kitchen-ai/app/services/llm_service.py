import json
import asyncio
from app.agents.cs_agent import cs_agent
from app.services.semantic_cache_service import semantic_cache
from langchain_core.messages import HumanMessage

# 回调 Java 查 MySQL 实时数据的工具：其回答含库存/配料快照，写入缓存会向其他用户散发过期数据
DYNAMIC_TOOLS = {"check_dish_inventory", "get_dish_ingredients"}


class ChatService:
    @staticmethod
    async def generate_chat_stream(message: str, question_vector: list[float] | None = None):
        # 用来收集完整回复以便缓存
        full_response = ""
        dynamic_tool_invoked = False

        # 异步流式调用 Agent
        # 注意：astream_events v1 已被弃用，建议后续升至 v2，此处先维持功能不变
        async for event in cs_agent.astream_events(
            {"messages": [HumanMessage(content=message)]},
            version="v1"
        ):
            kind = event["event"]
            if kind == "on_chat_model_stream":
                chunk = event["data"]["chunk"]
                if chunk.content:
                    full_response += chunk.content
                    # 按照 SSE 格式返回数据
                    yield f"data: {json.dumps({'content': chunk.content})}\n\n"
            elif kind == "on_tool_start" and event.get("name") in DYNAMIC_TOOLS:
                dynamic_tool_invoked = True

        # 只缓存非动态回答：纯知识问答与 RAG 推荐（search_dish_by_preference 查的是知识库，
        # 知识库更新会通过 kb_version 指纹使旧缓存失效）均可缓存；含实时工具结果的不缓存
        if full_response and not dynamic_tool_invoked:
            semantic_cache.store(message, full_response, question_vector=question_vector)

        yield "data: [DONE]\n\n"

    @staticmethod
    async def generate_cached_stream(cached_response: str):
        # 如果有缓存，稍微切分一下模拟流式
        chunk_size = 10
        for i in range(0, len(cached_response), chunk_size):
            chunk = cached_response[i:i+chunk_size]
            yield f"data: {json.dumps({'content': chunk})}\n\n"
            await asyncio.sleep(0.01)
        yield "data: [DONE]\n\n"

    @classmethod
    def get_chat_response_generator(cls, message: str):
        """
        核心业务逻辑：语义缓存命中则返回缓存流，否则调用大模型流。
        未命中时 lookup 已算好的问题向量随调用传递，写缓存时无需重复 embedding。
        """
        cached_response, question_vector = semantic_cache.lookup(message)

        if cached_response:
            return cls.generate_cached_stream(cached_response)

        return cls.generate_chat_stream(message, question_vector=question_vector)
