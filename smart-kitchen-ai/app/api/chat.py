import asyncio
import contextlib
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from typing import Optional, List

from app.config import settings
from app.services.llm_service import ChatService
from app.services.chat_cancellation import cancel_chat, is_cancelled, register_chat
from app.utils.auth import require_chat_auth, check_rate_limit

router = APIRouter(prefix="/ai", tags=["AI Chat"])


class ChatMessage(BaseModel):
    role: str  # "user" | "assistant"
    content: str


class ChatRequest(BaseModel):
    message: str = ""  # 兼容旧客户端：单条消息
    conversation_id: Optional[str] = None  # 小程序本地生成即可，用于日志与限流兜底
    messages: Optional[List[ChatMessage]] = None  # 最近若干轮历史（含当前条）
    request_id: Optional[str] = None  # 小程序为每次流式请求生成，用于取消


class ChatCancelRequest(BaseModel):
    request_id: str


def _principal_key(principal: dict) -> str:
    """取消权限的主体标识，与聊天限流的用户优先级一致。"""
    return str(principal.get("userId") or principal.get("sub") or principal.get("_ip") or "unknown")


def _resolve_messages(request: ChatRequest) -> tuple[str, list[dict]]:
    """
    归一化多轮输入：返回 (当前用户消息, 之前的历史)。
    兼容旧调用：只有 message 时历史为空，行为与单轮一致。
    """
    msgs = [
        {"role": m.role, "content": m.content}
        for m in (request.messages or [])
        if m.role in ("user", "assistant") and m.content and m.content.strip()
    ]
    current = (request.message or "").strip()
    if current:
        # 避免与 messages 末尾的当前条重复
        if not msgs or msgs[-1]["role"] != "user" or msgs[-1]["content"].strip() != current:
            msgs.append({"role": "user", "content": current})
    for i in range(len(msgs) - 1, -1, -1):
        if msgs[i]["role"] == "user":
            return msgs[i]["content"].strip(), msgs[:i]
    return "", []


@router.post("/chat")
async def ai_chat(request: ChatRequest, req: Request, principal=Depends(require_chat_auth)):
    check_rate_limit(principal)

    message, history = _resolve_messages(request)
    if not message:
        raise HTTPException(status_code=400, detail="message 不能为空")

    # 只保留最近 N-1 条历史（含当前共 N 条进 Agent），禁止全量历史
    history = history[-(settings.chat_history_max_messages - 1):]
    request_id = (request.request_id or uuid.uuid4().hex).strip()
    if not request_id or len(request_id) > 64:
        raise HTTPException(status_code=400, detail="request_id 不合法")
    register_chat(request_id, _principal_key(principal))

    # 核心业务逻辑移交至 Service 层处理，Controller 层仅负责路由与请求响应包装
    cancel_event = asyncio.Event()
    generator = ChatService.get_chat_response_generator(
        message,
        history=history,
        conversation_id=request.conversation_id,
        request_id=request_id,
        cancel_event=cancel_event,
    )

    async def watch_disconnect(stop: asyncio.Event):
        """模型/工具暂时没有产出时，仍定期探测客户端是否已断开。"""
        while not stop.is_set():
            if is_cancelled(request_id) or await req.is_disconnected():
                cancel_chat(request_id)
                cancel_event.set()
                return
            try:
                await asyncio.wait_for(stop.wait(), timeout=0.25)
            except asyncio.TimeoutError:
                pass

    async def event_stream():
        stop = asyncio.Event()
        watcher = asyncio.create_task(watch_disconnect(stop))
        completed = False
        try:
            async for chunk in generator:
                # 正常有 token 时立即检查，避免等到 watcher 的下一次检测。
                if is_cancelled(request_id) or await req.is_disconnected():
                    cancel_chat(request_id)
                    cancel_event.set()
                    break
                yield chunk
                if chunk == "data: [DONE]\n\n":
                    completed = True
        finally:
            stop.set()
            watcher.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await watcher
            # StreamingResponse 因断连取消生成器时也会进入 finally。
            if not completed:
                cancel_chat(request_id)
                cancel_event.set()

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream"
    )


@router.post("/chat/cancel")
async def cancel_ai_chat(request: ChatCancelRequest, principal=Depends(require_chat_auth)):
    """用户点击停止生成时调用；只能取消本人发起的客服请求。"""
    request_id = request.request_id.strip()
    if not request_id or len(request_id) > 64:
        raise HTTPException(status_code=400, detail="request_id 不合法")
    if not cancel_chat(request_id, _principal_key(principal)):
        raise HTTPException(status_code=404, detail="聊天请求不存在或无权取消")
    return {"code": 200, "message": "已取消"}
