"""客服流式请求的取消状态。

取消状态放 Redis，因而前端取消接口、SSE 断连检测和 Agent 工具节点即使
不在同一个进程，也能读取同一份状态。Redis 不可用时只记录日志：客服仍可用，
但无法保证及时停止这一轮生成。
"""
import logging

from app.config import settings
from app.db.redis_client import redis_client

logger = logging.getLogger(__name__)

CANCEL_KEY_PREFIX = "chat:cancel:"
OWNER_KEY_PREFIX = "chat:owner:"


def _cancel_key(request_id: str) -> str:
    return f"{CANCEL_KEY_PREFIX}{request_id}"


def _owner_key(request_id: str) -> str:
    return f"{OWNER_KEY_PREFIX}{request_id}"


def register_chat(request_id: str, owner: str) -> None:
    """登记请求所属用户，取消接口据此拒绝跨用户取消。"""
    try:
        redis_client.setex(_owner_key(request_id), settings.chat_cancel_ttl_seconds, owner)
    except Exception as exc:
        logger.warning("Redis 不可用，无法登记聊天请求 %s：%s", request_id, exc)


def cancel_chat(request_id: str, owner: str | None = None) -> bool:
    """写入取消标记；指定 owner 时必须与登记用户一致。"""
    try:
        if owner is not None:
            registered_owner = redis_client.get(_owner_key(request_id))
            if registered_owner != owner:
                return False
        redis_client.setex(_cancel_key(request_id), settings.chat_cancel_ttl_seconds, "1")
        return True
    except Exception as exc:
        logger.warning("Redis 不可用，无法取消聊天请求 %s：%s", request_id, exc)
        return False


def is_cancelled(request_id: str | None) -> bool:
    """当前请求是否已取消。Redis 读取失败时降级为继续执行。"""
    if not request_id:
        return False
    try:
        return bool(redis_client.exists(_cancel_key(request_id)))
    except Exception as exc:
        logger.warning("Redis 不可用，跳过聊天取消检查 %s：%s", request_id, exc)
        return False
