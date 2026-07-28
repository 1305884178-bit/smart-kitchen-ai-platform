from fastapi import APIRouter
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from app.services.llm_service import ChatService

router = APIRouter(prefix="/ai", tags=["AI Chat"])

class ChatRequest(BaseModel):
    message: str

@router.post("/chat")
async def ai_chat(request: ChatRequest):
    message = request.message.strip()
    
    # 核心业务逻辑移交至 Service 层处理，Controller 层仅负责路由与请求响应包装
    generator = ChatService.get_chat_response_generator(message)
    
    return StreamingResponse(
        generator,
        media_type="text/event-stream"
    )
