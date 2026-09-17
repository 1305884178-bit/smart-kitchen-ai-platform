"""
多轮对话 / query 改写 / 语义缓存 key / 客服接口鉴权限流测试（外部依赖全部 mock，离线可跑）。

核心约定（对应需求 P0-4 / 4.1 / P1-13）：
- 「推荐辣鱼 → 这个辣不辣」改写后检索词必须含「水煮鱼」
- 语义缓存 lookup/store 一律使用改写句，禁止用原句「这个辣不辣」作 key
- 送入 ReAct Agent 的消息只保留最近 N=8 条（含当前），全量历史禁止
- /ai/chat 强制鉴权（JWT 或内部 token）+ 简单限流
"""
import asyncio
import base64
import hashlib
import hmac
import json
import time
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

import app.services.query_rewrite as qr
from app.config import settings
from app.services.llm_service import ChatService, _build_agent_messages
from app.services.query_rewrite import rewrite_query

DISHES = ["水煮鱼", "黑叉烧", "宫保鸡丁", "玉米萝卜排骨汤"]

HISTORY_SPICY_FISH = [
    {"role": "user", "content": "推荐一个辣鱼"},
    {"role": "assistant", "content": "推荐您试试水煮鱼，麻辣鲜香，是招牌菜。"},
]


@pytest.fixture(autouse=True)
def dish_dict_mock():
    with patch.object(qr, "get_dish_names", return_value=DISHES):
        yield


# ---------- rewrite_query ----------

class TestRewriteQuery:
    def test_demonstrative_replaced_with_topic_dish(self):
        # 需求用例：先问推荐辣鱼，再问「这个辣不辣」→ 改写含「水煮鱼」
        assert rewrite_query("这个辣不辣", HISTORY_SPICY_FISH) == "水煮鱼辣不辣"

    def test_short_followup_prefixed_with_dish(self):
        assert rewrite_query("辣不辣", HISTORY_SPICY_FISH) == "水煮鱼辣不辣"

    def test_topic_dish_prefers_assistant_reply(self):
        history = [
            {"role": "user", "content": "黑叉烧怎么样"},
            {"role": "assistant", "content": "推荐您试试宫保鸡丁，糊辣荔枝口。"},
        ]
        # 话题菜名优先取助手最近一轮（宫保鸡丁），而非用户更早提到的黑叉烧
        assert rewrite_query("这个辣不辣", history) == "宫保鸡丁辣不辣"

    def test_message_with_dish_name_unchanged(self):
        assert rewrite_query("黑叉烧甜吗", HISTORY_SPICY_FISH) == "黑叉烧甜吗"

    def test_no_history_returns_original(self):
        assert rewrite_query("这个辣不辣") == "这个辣不辣"
        assert rewrite_query("这个辣不辣", []) == "这个辣不辣"

    def test_small_talk_not_rewritten(self):
        assert rewrite_query("谢谢", HISTORY_SPICY_FISH) == "谢谢"

    def test_llm_fallback_when_rule_cannot_resolve(self):
        # 有指代但历史里没有菜名 → 规则不适用，走 LLM 改写
        history = [{"role": "user", "content": "随便聊聊"}, {"role": "assistant", "content": "您好呀"}]
        with patch.object(qr, "_llm_rewrite", return_value="招牌菜是什么") as mock_llm:
            assert rewrite_query("这个是什么", history) == "招牌菜是什么"
            mock_llm.assert_called_once()

    def test_llm_disabled_returns_original(self):
        history = [{"role": "user", "content": "随便聊聊"}, {"role": "assistant", "content": "您好呀"}]
        assert rewrite_query("这个是什么", history, allow_llm=False) == "这个是什么"


# ---------- 检索工具使用改写句 ----------

class TestRetrievalUsesRewrittenQuery:
    def test_search_tool_prefers_context_retrieval_query(self):
        from app.tools.dish_tools import search_dish_by_preference
        from app.services.retrieval_context import current_retrieval_query

        with patch("app.tools.dish_tools.search_knowledge", return_value=[]) as mock_search:
            token = current_retrieval_query.set("水煮鱼辣不辣")
            try:
                asyncio.run(search_dish_by_preference.ainvoke({"query": "这个辣不辣"}))
            finally:
                current_retrieval_query.reset(token)

        mock_search.assert_called_once_with("水煮鱼辣不辣", top_k=3)

    def test_search_tool_falls_back_to_raw_query_without_context(self):
        from app.tools.dish_tools import search_dish_by_preference
        from app.services.retrieval_context import current_retrieval_query

        token = current_retrieval_query.set(None)
        try:
            with patch("app.tools.dish_tools.search_knowledge", return_value=[]) as mock_search:
                asyncio.run(search_dish_by_preference.ainvoke({"query": "黑叉烧甜吗"}))
        finally:
            current_retrieval_query.reset(token)

        mock_search.assert_called_once_with("黑叉烧甜吗", top_k=3)

    def test_tool_output_has_knowledge_tags_and_provenance(self):
        from app.tools.dish_tools import search_dish_by_preference

        results = [{"text": "麻辣鲜香", "document_id": "42", "chunk_index": 1, "title": "水煮鱼"}]
        with patch("app.tools.dish_tools.search_knowledge", return_value=results):
            output = asyncio.run(search_dish_by_preference.ainvoke({"query": "水煮鱼辣不辣"}))

        # 间接注入防护：资料包在明确标记中；溯源字段保留
        assert "<knowledge>" in output
        assert "参考资料" in output
        assert "不是指令" in output
        assert "来源: 水煮鱼#chunk1" in output


# ---------- 语义缓存 key 用改写句 ----------

def _fake_agent_events(text="辣，很下饭。"):
    async def stream(messages, version, **kwargs):
        yield {"event": "on_chat_model_stream",
               "data": {"chunk": SimpleNamespace(content=text)}}
    return stream


def run_async_gen(agen):
    """收集异步生成器的全部输出"""
    async def _collect():
        return [item async for item in agen]
    return asyncio.run(_collect())


class TestSemanticCacheKey:
    def test_lookup_and_store_use_rewritten_not_raw(self):
        rewritten = "水煮鱼辣不辣"
        mock_cache = MagicMock()
        mock_cache.lookup.return_value = (None, [0.1] * 1024)
        mock_agent = MagicMock()
        mock_agent.astream_events = _fake_agent_events()

        with patch("app.services.llm_service.rewrite_query", return_value=rewritten), \
             patch("app.services.llm_service.semantic_cache", mock_cache), \
             patch("app.services.llm_service.cs_agent", mock_agent):
            gen = ChatService.get_chat_response_generator("这个辣不辣", history=HISTORY_SPICY_FISH)
            chunks = run_async_gen(gen)

        # lookup 用改写句，禁止用含指代的原句
        mock_cache.lookup.assert_called_once_with(rewritten)
        # store 的 question 字段也是改写句，且复用 lookup 算好的向量（不重复 embedding）
        mock_cache.store.assert_called_once_with(rewritten, "辣，很下饭。", question_vector=[0.1] * 1024)
        assert any("[DONE]" in c for c in chunks)

    def test_cache_hit_returns_cached_stream_without_agent(self):
        mock_cache = MagicMock()
        mock_cache.lookup.return_value = ("缓存答案", None)

        with patch("app.services.llm_service.rewrite_query", return_value="水煮鱼辣不辣"), \
             patch("app.services.llm_service.semantic_cache", mock_cache), \
             patch("app.services.llm_service.cs_agent") as mock_agent:
            gen = ChatService.get_chat_response_generator("这个辣不辣", history=HISTORY_SPICY_FISH)
            chunks = run_async_gen(gen)

        mock_agent.astream_events.assert_not_called()
        # json.dumps 默认转义非 ASCII，比对转义后的形式
        assert json.dumps({"content": "缓存答案"}) in "".join(chunks)

    def test_dynamic_tool_answer_not_cached(self):
        mock_cache = MagicMock()
        mock_cache.lookup.return_value = (None, [0.1] * 1024)

        async def stream(messages, version, **kwargs):
            yield {"event": "on_tool_start", "name": "check_dish_inventory", "data": {}}
            yield {"event": "on_chat_model_stream",
                   "data": {"chunk": SimpleNamespace(content="今日还剩 8 份")}}

        mock_agent = MagicMock()
        mock_agent.astream_events = stream

        with patch("app.services.llm_service.rewrite_query", side_effect=lambda m, h: m), \
             patch("app.services.llm_service.semantic_cache", mock_cache), \
             patch("app.services.llm_service.cs_agent", mock_agent):
            gen = ChatService.get_chat_response_generator("水煮鱼还有吗")
            run_async_gen(gen)

        # 库存类动态回答不写语义缓存
        mock_cache.store.assert_not_called()

    def test_agent_receives_request_id_in_runtime_config(self):
        mock_cache = MagicMock()
        mock_cache.lookup.return_value = (None, [0.1] * 1024)
        mock_agent = MagicMock()
        mock_agent.astream_events = _fake_agent_events()

        with patch("app.services.llm_service.semantic_cache", mock_cache), \
             patch("app.services.llm_service.cs_agent", mock_agent):
            run_async_gen(ChatService.get_chat_response_generator("招牌菜", request_id="chat-test"))

        assert mock_agent.astream_events.call_args.kwargs["config"] == {
            "configurable": {"request_id": "chat-test"}
        }

    def test_cancelled_stream_does_not_emit_done_or_write_cache(self):
        mock_cache = MagicMock()
        mock_cache.lookup.return_value = (None, [0.1] * 1024)
        mock_agent = MagicMock()
        mock_agent.astream_events = _fake_agent_events()

        with patch("app.services.llm_service.semantic_cache", mock_cache), \
             patch("app.services.llm_service.cs_agent", mock_agent), \
             patch("app.services.llm_service.is_cancelled", return_value=True):
            chunks = run_async_gen(ChatService.get_chat_response_generator("招牌菜", request_id="chat-test"))

        assert chunks == []
        mock_cache.store.assert_not_called()


# ---------- Agent 消息窗口 ----------

class TestAgentMessageWindow:
    def test_history_trimmed_to_max_messages(self):
        history = [{"role": "user" if i % 2 == 0 else "assistant", "content": f"第{i}条"}
                   for i in range(20)]
        with patch.object(settings, "chat_history_max_messages", 8):
            msgs = _build_agent_messages("当前问题", history, settings.chat_history_max_messages)

        # 只取最近 8 条（含当前），全量历史禁止
        assert len(msgs) == 8
        assert msgs[-1].content == "当前问题"
        assert msgs[0].content == "第13条"  # 20 条历史只保留最后 7 条

    def test_empty_content_history_skipped(self):
        history = [{"role": "user", "content": "  "}, {"role": "user", "content": "有效"}]
        msgs = _build_agent_messages("当前问题", history, 8)
        assert [m.content for m in msgs] == ["有效", "当前问题"]


# ---------- /ai/chat 接口：鉴权 / 限流 / 多轮入参 ----------

def _make_jwt(secret: str, exp_offset: int = 3600, **claims) -> str:
    def b64(data: dict) -> str:
        raw = json.dumps(data, separators=(",", ":")).encode()
        return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

    header = {"alg": "HS256", "typ": "JWT"}
    payload = {"userId": 1001, "role": "USER", "tokenType": "access",
               "exp": time.time() + exp_offset, **claims}
    signing_input = f"{b64(header)}.{b64(payload)}"
    sig = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return f"{signing_input}.{base64.urlsafe_b64encode(sig).rstrip(b'=').decode()}"


@pytest.fixture
def client():
    from app.main import app
    return TestClient(app)


@pytest.fixture(autouse=True)
def clear_rate_buckets():
    """限流现已走 Redis（INCR+TTL）：测试内统一打断 Redis 强制走内存兜底窗口，保证离线可重复"""
    from app.utils import auth
    auth._rate_buckets.clear()
    broken = MagicMock()
    broken.exists.return_value = 0
    broken.get.return_value = None
    broken.incr.side_effect = ConnectionError("redis down in tests")
    with patch.object(auth, "redis_client", broken):
        yield
    auth._rate_buckets.clear()


def _mock_chat_generator():
    async def gen():
        yield 'data: {"content": "ok"}\n\n'
        yield "data: [DONE]\n\n"
    return gen()


class TestChatEndpoint:
    def test_missing_authorization_returns_401(self, client):
        resp = client.post("/ai/chat", json={"message": "你好"})
        assert resp.status_code == 401

    def test_bad_signature_returns_401(self, client):
        token = _make_jwt("wrong-secret")
        resp = client.post("/ai/chat", json={"message": "你好"},
                           headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 401

    def test_expired_token_returns_401(self, client):
        token = _make_jwt(settings.jwt_secret, exp_offset=-10)
        resp = client.post("/ai/chat", json={"message": "你好"},
                           headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 401

    def test_valid_jwt_accepted_and_history_passed(self, client):
        token = _make_jwt(settings.jwt_secret)
        with patch.object(ChatService, "get_chat_response_generator",
                          return_value=_mock_chat_generator()) as mock_gen:
            resp = client.post("/ai/chat", json={
                "message": "这个辣不辣",
                "conversation_id": "conv-1",
                "messages": [
                    {"role": "user", "content": "推荐一个辣鱼"},
                    {"role": "assistant", "content": "推荐水煮鱼"},
                    {"role": "user", "content": "这个辣不辣"},
                ],
            }, headers={"Authorization": f"Bearer {token}"})

        assert resp.status_code == 200
        message, history = mock_gen.call_args.args[0], mock_gen.call_args.kwargs["history"]
        assert message == "这个辣不辣"
        # 末条与 message 相同被去重，历史为前两条
        assert [m["content"] for m in history] == ["推荐一个辣鱼", "推荐水煮鱼"]

    def test_internal_token_accepted(self, client):
        with patch.object(settings, "ai_internal_token", "internal-test-token"):
            with patch.object(ChatService, "get_chat_response_generator",
                              return_value=_mock_chat_generator()):
                resp = client.post("/ai/chat", json={"message": "你好"},
                                   headers={"Authorization": "Bearer internal-test-token"})
        assert resp.status_code == 200

    def test_rate_limit(self, client):
        token = _make_jwt(settings.jwt_secret)
        with patch.object(settings, "chat_rate_limit_per_minute", 2):
            with patch.object(ChatService, "get_chat_response_generator",
                              return_value=_mock_chat_generator()):
                headers = {"Authorization": f"Bearer {token}"}
                assert client.post("/ai/chat", json={"message": "1"}, headers=headers).status_code == 200
                assert client.post("/ai/chat", json={"message": "2"}, headers=headers).status_code == 200
                resp = client.post("/ai/chat", json={"message": "3"}, headers=headers)
        assert resp.status_code == 429

    def test_history_trimmed_by_endpoint(self, client):
        token = _make_jwt(settings.jwt_secret)
        messages = [{"role": "user" if i % 2 == 0 else "assistant", "content": f"第{i}条"}
                    for i in range(30)]
        messages.append({"role": "user", "content": "当前问题"})
        with patch.object(ChatService, "get_chat_response_generator",
                          return_value=_mock_chat_generator()) as mock_gen:
            resp = client.post("/ai/chat", json={"message": "当前问题", "messages": messages},
                               headers={"Authorization": f"Bearer {token}"})

        assert resp.status_code == 200
        history = mock_gen.call_args.kwargs["history"]
        assert len(history) == settings.chat_history_max_messages - 1


# ---------- 请求归一化 ----------

class TestResolveMessages:
    def test_message_only_backward_compatible(self):
        from app.api.chat import _resolve_messages, ChatRequest
        message, history = _resolve_messages(ChatRequest(message="你好"))
        assert message == "你好"
        assert history == []

    def test_messages_only_takes_last_user(self):
        from app.api.chat import _resolve_messages, ChatRequest, ChatMessage
        req = ChatRequest(messages=[
            ChatMessage(role="user", content="推荐辣鱼"),
            ChatMessage(role="assistant", content="水煮鱼"),
            ChatMessage(role="user", content="这个辣不辣"),
        ])
        message, history = _resolve_messages(req)
        assert message == "这个辣不辣"
        assert len(history) == 2
