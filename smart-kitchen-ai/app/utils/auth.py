"""
最小可用鉴权与限流。

- /ai/chat：校验与 Java 端一致的 HS256 JWT（共享密钥，jjwt 的
  Keys.hmacShaKeyFor(secret.getBytes) 等价于 HMAC-SHA256(secret)）；
  另接受服务间内部 token（AI_INTERNAL_TOKEN）。
- /ai/knowledge、/ai/predict：仅在配置 AI_INTERNAL_TOKEN 后强制校验内部 token，
  未配置时保持开放（本地开发默认），避免把服务做成完全公网裸奔的同时不破坏现有联调。
- 限流：内存滑动窗口，按 userId / 内部主体 / IP 计数。
"""
import base64
import hashlib
import hmac
import json
import logging
import time
from collections import defaultdict, deque
from threading import Lock

from fastapi import Header, HTTPException, Request

from app.config import settings

logger = logging.getLogger(__name__)


def _b64url_decode(data: str) -> bytes:
    data += "=" * (-len(data) % 4)
    return base64.urlsafe_b64decode(data.encode())


def verify_jwt(token: str) -> dict | None:
    """手工校验 HS256 JWT，返回 payload；签名不符或已过期返回 None。"""
    try:
        header_b64, payload_b64, sig_b64 = token.split(".")
        signing_input = f"{header_b64}.{payload_b64}".encode()
        expected = hmac.new(
            settings.jwt_secret.encode("utf-8"), signing_input, hashlib.sha256
        ).digest()
        if not hmac.compare_digest(expected, _b64url_decode(sig_b64)):
            return None
        payload = json.loads(_b64url_decode(payload_b64))
        exp = payload.get("exp")
        if exp is not None and float(exp) < time.time():
            return None
        return payload
    except Exception:
        return None


def require_chat_auth(request: Request, authorization: str = Header(None)) -> dict:
    """/ai/chat 强制鉴权：用户 JWT 或内部 token。"""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="缺少 Authorization 头")
    token = authorization[len("Bearer "):].strip()
    payload = verify_jwt(token)
    if payload is None and settings.ai_internal_token and hmac.compare_digest(
        token, settings.ai_internal_token
    ):
        payload = {"sub": "internal"}
    if payload is None:
        raise HTTPException(status_code=401, detail="token 无效或已过期")
    payload["_ip"] = request.client.host if request.client else "unknown"
    return payload


def verify_internal_token(authorization: str = Header(None)):
    """服务间接口鉴权：仅在配置 AI_INTERNAL_TOKEN 后强制（默认不破坏本地联调）。"""
    if not settings.ai_internal_token:
        return
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="缺少 Authorization 头")
    if not hmac.compare_digest(authorization[len("Bearer "):].strip(), settings.ai_internal_token):
        raise HTTPException(status_code=401, detail="内部 token 无效")


# ---------- 简单滑动窗口限流（内存实现，单进程够用） ----------

_rate_lock = Lock()
_rate_buckets: dict[str, deque] = defaultdict(deque)


def check_rate_limit(principal: dict):
    limit = settings.chat_rate_limit_per_minute
    if limit <= 0:
        return
    key = str(
        principal.get("userId") or principal.get("sub") or principal.get("_ip") or "unknown"
    )
    now = time.time()
    with _rate_lock:
        bucket = _rate_buckets[key]
        while bucket and now - bucket[0] > 60:
            bucket.popleft()
        if len(bucket) >= limit:
            raise HTTPException(status_code=429, detail="请求过于频繁，请稍后再试")
        bucket.append(now)
