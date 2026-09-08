"""
最小可用鉴权与限流。

- /ai/chat：校验与 Java 端一致的 HS256 JWT（共享密钥，jjwt 的
  Keys.hmacShaKeyFor(secret.getBytes) 等价于 HMAC-SHA256(secret)）；
  另接受服务间内部 token（AI_INTERNAL_TOKEN）。
  与 Java JwtInterceptor 对齐的三项校验（本地完成，不回调 Java）：
  1. tokenType 必须为 "access"（拒绝 refresh token）；
  2. jti 不得在 Redis 黑名单 auth:blacklist:{jti}（登出拉黑）；
  3. 用户级吊销 auth:revoke:{userId}：签发时间早于吊销时间戳则拒绝
     （对齐 RedisTokenStore.isUserRevoked，iat 秒 * 1000 与毫秒时间戳比较）。
  Redis 不可用时验签通过仍放行并打 warning，与语义缓存降级策略一致。
- /ai/knowledge、/ai/predict：仅在配置 AI_INTERNAL_TOKEN 后强制校验内部 token，
  未配置时保持开放（本地开发默认），避免把服务做成完全公网裸奔的同时不破坏现有联调。
- 限流：优先 Redis（INCR + TTL 固定窗口近似滑动窗口），按 userId / 内部主体 / IP 计数；
  Redis 挂了降级为进程内存滑动窗口，不引入新中间件。
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
from app.db.redis_client import redis_client

logger = logging.getLogger(__name__)

# 与 Java RedisTokenStore 一致的 Redis key 前缀
BLACKLIST_KEY_PREFIX = "auth:blacklist:"
REVOKE_KEY_PREFIX = "auth:revoke:"
# 聊天限流 key 前缀
RATE_LIMIT_KEY_PREFIX = "chat:rate:"


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


def _is_blacklisted(jti) -> bool:
    """jti 是否在登出黑名单；Redis 不可用时放行（返回 False）并打 warning。"""
    if not jti:
        return False
    try:
        return bool(redis_client.exists(f"{BLACKLIST_KEY_PREFIX}{jti}"))
    except Exception as e:
        logger.warning(f"Redis 不可用，跳过 JWT 黑名单检查（jti={jti}）：{e}")
        return False


def _is_user_revoked(user_id, iat_seconds) -> bool:
    """
    用户级吊销检查，对齐 Java RedisTokenStore.isUserRevoked：
    token 签发时间（毫秒）早于 auth:revoke:{userId} 记录的时间戳则视为已吊销。
    Redis 不可用时放行（返回 False）并打 warning。
    """
    if user_id is None:
        return False
    try:
        raw = redis_client.get(f"{REVOKE_KEY_PREFIX}{user_id}")
    except Exception as e:
        logger.warning(f"Redis 不可用，跳过用户吊销检查（userId={user_id}）：{e}")
        return False
    if raw is None:
        return False
    try:
        issued_at_ms = int(float(iat_seconds or 0) * 1000)
        return issued_at_ms < int(raw)
    except (TypeError, ValueError):
        return False


def require_chat_auth(request: Request, authorization: str = Header(None)) -> dict:
    """/ai/chat 强制鉴权：用户 JWT 或内部 token。"""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="缺少 Authorization 头")
    token = authorization[len("Bearer "):].strip()
    payload = verify_jwt(token)
    if payload is not None:
        # 1. 必须是 access token，拒绝 refresh（对齐 Java JwtInterceptor.isAccessToken；
        #    缺失/为 null 时与 Java getTokenType 一致按 access 处理）
        if (payload.get("tokenType") or "access") != "access":
            raise HTTPException(status_code=401, detail="请使用 Access Token")
        # 2. jti 黑名单（登出拉黑）
        if _is_blacklisted(payload.get("jti")):
            raise HTTPException(status_code=401, detail="token 无效或已过期")
        # 3. 用户级吊销（全端下线）
        if _is_user_revoked(payload.get("userId"), payload.get("iat")):
            raise HTTPException(status_code=401, detail="token 无效或已过期")
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


def internal_auth_headers() -> dict:
    """构造调用 Java 服务间接口的请求头；未配置 AI_INTERNAL_TOKEN 时为空头（本地联调）。"""
    if settings.ai_internal_token:
        return {"Authorization": f"Bearer {settings.ai_internal_token}"}
    return {}


# ---------- 限流：Redis（INCR+TTL）优先，进程内存滑动窗口兜底 ----------

_rate_lock = Lock()
_rate_buckets: dict[str, deque] = defaultdict(deque)


def _check_rate_limit_memory(key: str, limit: int):
    """进程内存滑动窗口（单进程兜底实现）"""
    now = time.time()
    with _rate_lock:
        bucket = _rate_buckets[key]
        while bucket and now - bucket[0] > 60:
            bucket.popleft()
        if len(bucket) >= limit:
            raise HTTPException(status_code=429, detail="请求过于频繁，请稍后再试")
        bucket.append(now)


def check_rate_limit(principal: dict):
    limit = settings.chat_rate_limit_per_minute
    if limit <= 0:
        return
    key = str(
        principal.get("userId") or principal.get("sub") or principal.get("_ip") or "unknown"
    )
    try:
        # Redis 固定窗口近似滑动窗口：INCR 首次置 60s TTL，超限拒绝
        redis_key = f"{RATE_LIMIT_KEY_PREFIX}{key}"
        count = redis_client.incr(redis_key)
        if count == 1:
            redis_client.expire(redis_key, 60)
        if count > limit:
            raise HTTPException(status_code=429, detail="请求过于频繁，请稍后再试")
    except HTTPException:
        raise
    except Exception as e:
        # Redis 挂了降级为内存窗口，不把客服打挂（与语义缓存降级一致）
        logger.warning(f"Redis 不可用，聊天限流降级为进程内存窗口：{e}")
        _check_rate_limit_memory(key, limit)
