from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from typing import Optional, List

from app.config import settings
from app.services.llm_service import ChatService
from app.utils.auth import require_chat_auth, check_rate_limit

router = APIRouter(prefix="/ai", tags=["AI Chat"])


class ChatMessage(BaseModel):
    role: str  # "user" | "assistant"
    content: str


class ChatRequest(BaseModel):
    message: str = ""  # 兼容旧客户端：单条消息
    conversation_id: Optional[str] = None  # 小程序本地生成即可，用于日志与限流兜底
    messages: Optional[List[ChatMessage]] = None  # 最近若干轮历史（含当前条）


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

    # 核心业务逻辑移交至 Service 层处理，Controller 层仅负责路由与请求响应包装
    generator = ChatService.get_chat_response_generator(
        message, history=history, conversation_id=request.conversation_id
    )

    return StreamingResponse(
        generator,
        media_type="text/event-stream"
    )
