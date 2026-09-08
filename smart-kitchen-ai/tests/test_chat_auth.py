"""
/ai/chat 鉴权与限流对齐 Java 的测试（Redis 全部 mock，离线可跑）。

核心约定（对应 spec 8.1 与服务间安全改造）：
- 只接受 tokenType == "access" 的 JWT，refresh token 拒绝
- jti 命中 Redis 黑名单 auth:blacklist:{jti} → 401（对齐 RedisTokenStore.isBlacklisted）
- 用户级吊销 auth:revoke:{userId}：iat*1000 < 吊销时间戳 → 401（对齐 isUserRevoked）
- Redis 不可用时验签通过仍放行（打 warning），不把客服打挂
- 限流走 Redis INCR+TTL；Redis 挂了降级内存滑动窗口
"""
import base64
import hashlib
import hmac
import json
import time
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.services.llm_service import ChatService
from app.utils import auth


def _make_jwt(secret: str, exp_offset: int = 3600, **claims) -> str:
    def b64(data: dict) -> str:
        raw = json.dumps(data, separators=(",", ":")).encode()
        return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

    header = {"alg": "HS256", "typ": "JWT"}
    payload = {"userId": 1001, "role": "USER", "tokenType": "access",
               "jti": "test-jti-1", "iat": time.time(),
               "exp": time.time() + exp_offset, **claims}
    signing_input = f"{b64(header)}.{b64(payload)}"
    sig = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return f"{signing_input}.{base64.urlsafe_b64encode(sig).rstrip(b'=').decode()}"


def _mock_chat_generator():
    async def gen():
        yield 'data: {"content": "ok"}\n\n'
        yield "data: [DONE]\n\n"
    return gen()


@pytest.fixture
def client():
    from app.main import app
    return TestClient(app)


@pytest.fixture(autouse=True)
def clear_rate_buckets():
    auth._rate_buckets.clear()
    yield
    auth._rate_buckets.clear()


@pytest.fixture
def redis_mock():
    """替换 auth 模块里的 redis 客户端；默认无黑名单、无吊销、限流计数从 1 开始"""
    mock = MagicMock()
    mock.exists.return_value = 0
    mock.get.return_value = None
    mock.incr.return_value = 1
    with patch.object(auth, "redis_client", mock):
        yield mock


class TestChatJwtAlignment:
    def test_valid_access_token_accepted(self, client, redis_mock):
        token = _make_jwt(settings.jwt_secret)
        with patch.object(ChatService, "get_chat_response_generator",
                          return_value=_mock_chat_generator()):
            resp = client.post("/ai/chat", json={"message": "你好"},
                               headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 200

    def test_refresh_token_rejected(self, client, redis_mock):
        # tokenType=refresh 的合法签名 token 也必须 401（对齐 Java isAccessToken）
        token = _make_jwt(settings.jwt_secret, tokenType="refresh")
        resp = client.post("/ai/chat", json={"message": "你好"},
                           headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 401

    def test_missing_token_type_defaults_to_access(self, client, redis_mock):
        # 兼容历史 token：无 tokenType 声明按 access 处理（对齐 Java getTokenType 默认值）
        token = _make_jwt_without_claim(settings.jwt_secret, "tokenType")
        with patch.object(ChatService, "get_chat_response_generator",
                          return_value=_mock_chat_generator()):
            resp = client.post("/ai/chat", json={"message": "你好"},
                               headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 200

    def test_expired_token_rejected(self, client, redis_mock):
        token = _make_jwt(settings.jwt_secret, exp_offset=-10)
        resp = client.post("/ai/chat", json={"message": "你好"},
                           headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 401

    def test_blacklisted_jti_rejected(self, client, redis_mock):
        # auth:blacklist:{jti} 存在 → 401
        redis_mock.exists.return_value = 1
        token = _make_jwt(settings.jwt_secret, jti="blacklisted-jti")
        resp = client.post("/ai/chat", json={"message": "你好"},
                           headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 401
        redis_mock.exists.assert_called_with("auth:blacklist:blacklisted-jti")

    def test_revoked_user_rejected(self, client, redis_mock):
        # iat*1000 < auth:revoke:{userId} 时间戳 → 401
        redis_mock.get.return_value = str(int(time.time() * 1000) + 60000)
        token = _make_jwt(settings.jwt_secret)
        resp = client.post("/ai/chat", json={"message": "你好"},
                           headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 401
        redis_mock.get.assert_called_with("auth:revoke:1001")

    def test_token_issued_after_revoke_accepted(self, client, redis_mock):
        # 吊销时间戳早于 token 签发时间 → 放行
        redis_mock.get.return_value = str(int(time.time() * 1000) - 60000)
        token = _make_jwt(settings.jwt_secret)
        with patch.object(ChatService, "get_chat_response_generator",
                          return_value=_mock_chat_generator()):
            resp = client.post("/ai/chat", json={"message": "你好"},
                               headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 200

    def test_redis_down_still_allows_valid_jwt(self, client):
        # Redis 挂了：黑名单/吊销检查跳过并放行（不阻断客服），限流降级内存窗口
        broken = MagicMock()
        broken.exists.side_effect = ConnectionError("redis down")
        broken.get.side_effect = ConnectionError("redis down")
        broken.incr.side_effect = ConnectionError("redis down")
        token = _make_jwt(settings.jwt_secret)
        with patch.object(auth, "redis_client", broken):
            with patch.object(ChatService, "get_chat_response_generator",
                              return_value=_mock_chat_generator()):
                resp = client.post("/ai/chat", json={"message": "你好"},
                                   headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 200


def _make_jwt_without_claim(secret: str, drop: str) -> str:
    def b64(data: dict) -> str:
        raw = json.dumps(data, separators=(",", ":")).encode()
        return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

    header = {"alg": "HS256", "typ": "JWT"}
    payload = {"userId": 1001, "role": "USER", "jti": "jti-x",
               "iat": time.time(), "exp": time.time() + 3600}
    payload.pop(drop, None)
    signing_input = f"{b64(header)}.{b64(payload)}"
    sig = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return f"{signing_input}.{base64.urlsafe_b64encode(sig).rstrip(b'=').decode()}"


class TestChatRateLimit:
    def test_redis_window_blocks_over_limit(self, client, redis_mock):
        # Redis INCR 超限 → 429；key 含 userId
        redis_mock.incr.return_value = 31  # 默认阈值 30
        token = _make_jwt(settings.jwt_secret)
        resp = client.post("/ai/chat", json={"message": "你好"},
                           headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 429
        redis_mock.incr.assert_called_with("chat:rate:1001")

    def test_redis_first_hit_sets_ttl(self, client, redis_mock):
        redis_mock.incr.return_value = 1
        token = _make_jwt(settings.jwt_secret)
        with patch.object(ChatService, "get_chat_response_generator",
                          return_value=_mock_chat_generator()):
            resp = client.post("/ai/chat", json={"message": "你好"},
                               headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 200
        redis_mock.expire.assert_called_with("chat:rate:1001", 60)

    def test_redis_down_falls_back_to_memory_window(self, client):
        broken = MagicMock()
        broken.exists.side_effect = ConnectionError("redis down")
        broken.get.side_effect = ConnectionError("redis down")
        broken.incr.side_effect = ConnectionError("redis down")
        token = _make_jwt(settings.jwt_secret)
        with patch.object(auth, "redis_client", broken):
            with patch.object(settings, "chat_rate_limit_per_minute", 2):
                with patch.object(ChatService, "get_chat_response_generator",
                                  return_value=_mock_chat_generator()):
                    headers = {"Authorization": f"Bearer {token}"}
                    assert client.post("/ai/chat", json={"message": "1"}, headers=headers).status_code == 200
                    assert client.post("/ai/chat", json={"message": "2"}, headers=headers).status_code == 200
                    resp = client.post("/ai/chat", json={"message": "3"}, headers=headers)
        assert resp.status_code == 429
