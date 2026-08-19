"""
语义缓存单元测试：覆盖阈值命中边界、降级策略、知识库版本指纹、动态工具不写缓存。
全部外部依赖（embedding API / Milvus / Redis / MySQL / LLM Agent）均 mock，可离线运行。
"""
import asyncio
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest

from app.config import settings
from app.services import semantic_cache_service as scs
from app.services.llm_service import ChatService


def run_async_gen(agen):
    """收集异步生成器的全部输出"""
    async def _collect():
        return [item async for item in agen]
    return asyncio.run(_collect())


def make_milvus_client(hits):
    client = MagicMock()
    client.search.return_value = [hits] if hits else [[]]
    return client


@pytest.fixture(autouse=True)
def fixed_threshold():
    with patch.object(settings, "semantic_cache_threshold", 0.92), \
         patch.object(settings, "semantic_cache_ttl_days", 7):
        yield


@pytest.fixture
def service():
    svc = scs.SemanticCacheService()
    with patch.object(scs, "embeddings") as mock_emb, \
         patch.object(scs, "redis_client") as mock_redis, \
         patch.object(svc, "_get_client") as mock_get_client, \
         patch.object(svc, "get_kb_version", return_value="kb_v1"):
        mock_emb.embed_query.return_value = [0.1] * 1024
        mock_redis.get.return_value = None
        svc.mocks = SimpleNamespace(
            embeddings=mock_emb, redis=mock_redis, get_client=mock_get_client
        )
        yield svc


# ---------- SemanticCacheService.lookup ----------

class TestLookup:
    def test_hit_above_threshold(self, service):
        hits = [{"distance": 0.95, "entity": {"answer": "招牌是黑叉烧", "question": "招牌菜"}}]
        service.mocks.get_client.return_value = make_milvus_client(hits)

        answer, vector = service.lookup("你们的招牌菜是什么")

        assert answer == "招牌是黑叉烧"
        assert vector == [0.1] * 1024

    def test_miss_below_threshold(self, service):
        hits = [{"distance": 0.90, "entity": {"answer": "答非所问", "question": "别的"}}]
        service.mocks.get_client.return_value = make_milvus_client(hits)

        answer, vector = service.lookup("水煮鱼辣不辣")

        assert answer is None
        assert vector is not None  # 向量仍返回供写缓存复用

    def test_miss_when_no_hits(self, service):
        service.mocks.get_client.return_value = make_milvus_client([])

        answer, _ = service.lookup("完全没人问过的问题")

        assert answer is None

    def test_filter_scoped_by_kb_version_and_freshness(self, service):
        client = make_milvus_client([])
        service.mocks.get_client.return_value = client

        service.lookup("任意问题")

        filter_expr = client.search.call_args.kwargs["filter"]
        assert 'kb_version == "kb_v1"' in filter_expr
        assert "created_at >=" in filter_expr

    def test_degrades_on_embedding_error(self, service):
        service.mocks.embeddings.embed_query.side_effect = RuntimeError("embedding API 不可用")

        answer, vector = service.lookup("任意问题")

        assert answer is None and vector is None  # 静默降级，不阻塞聊天主流程

    def test_degrades_on_milvus_error(self, service):
        service.mocks.get_client.side_effect = RuntimeError("milvus 不可用")

        answer, _ = service.lookup("任意问题")

        assert answer is None


# ---------- SemanticCacheService.store ----------

class TestStore:
    def test_insert_carries_kb_version_and_timestamp(self, service):
        client = make_milvus_client([])
        service.mocks.get_client.return_value = client

        service.store("招牌菜是什么", "黑叉烧", question_vector=[0.2] * 1024)

        row = client.insert.call_args.kwargs["data"][0]
        assert row["answer"] == "黑叉烧"
        assert row["question"] == "招牌菜是什么"
        assert row["kb_version"] == "kb_v1"
        assert isinstance(row["created_at"], int)
        # 复用传入的向量，不重复调 embedding
        service.mocks.embeddings.embed_query.assert_not_called()

    def test_embeds_when_vector_not_provided(self, service):
        client = make_milvus_client([])
        service.mocks.get_client.return_value = client

        service.store("问题", "回答")

        service.mocks.embeddings.embed_query.assert_called_once_with("问题")

    def test_silent_on_insert_error(self, service):
        client = make_milvus_client([])
        client.insert.side_effect = RuntimeError("写入失败")
        service.mocks.get_client.return_value = client

        service.store("问题", "回答", question_vector=[0.1] * 1024)  # 不应抛异常


# ---------- 知识库版本指纹 ----------

class TestKbVersion:
    def test_fingerprint_cached_in_redis(self):
        svc = scs.SemanticCacheService()
        with patch.object(scs, "redis_client") as mock_redis, \
             patch.object(scs, "get_db_connection") as mock_conn:
            mock_redis.get.return_value = "cached_fp"

            assert svc.get_kb_version() == "cached_fp"
            mock_conn.assert_not_called()  # 命中 Redis 时不再查 MySQL

    def test_fingerprint_changes_with_document_rows(self):
        def fingerprint_of(rows):
            svc = scs.SemanticCacheService()
            cursor = MagicMock()
            cursor.fetchall.return_value = rows
            conn = MagicMock()
            conn.cursor.return_value.__enter__ = MagicMock(return_value=cursor)
            conn.cursor.return_value.__exit__ = MagicMock(return_value=False)
            with patch.object(scs, "redis_client") as mock_redis, \
                 patch.object(scs, "get_db_connection", return_value=conn):
                mock_redis.get.return_value = None
                return svc.get_kb_version()

        base = [{"id": 1, "version": "1.0", "status": "active"}]
        added = base + [{"id": 2, "version": "1.0", "status": "active"}]
        archived = [{"id": 1, "version": "1.0", "status": "archived"}]

        assert fingerprint_of(base) != fingerprint_of(added)      # 新增文档 → 旧缓存失效
        assert fingerprint_of(base) != fingerprint_of(archived)   # 文档归档 → 旧缓存失效

    def test_fallback_unknown_on_mysql_error(self):
        svc = scs.SemanticCacheService()
        with patch.object(scs, "redis_client") as mock_redis, \
             patch.object(scs, "get_db_connection", side_effect=RuntimeError("MySQL 不可用")):
            mock_redis.get.return_value = None

            assert svc.get_kb_version() == "unknown"  # 降级但缓存仍可用


# ---------- ChatService 集成（mock Agent 与缓存层） ----------

def fake_agent_events(events):
    """构造假的 astream_events：调用后返回产出指定事件的异步生成器"""
    def _astream_events(*args, **kwargs):
        async def _gen():
            for e in events:
                yield e
        return _gen()
    return _astream_events


def stream_event(text):
    return {"event": "on_chat_model_stream", "data": {"chunk": SimpleNamespace(content=text)}}


def tool_start_event(name):
    return {"event": "on_tool_start", "name": name, "data": {"input": ""}}


class TestChatService:
    def test_cache_hit_returns_cached_stream_without_agent(self):
        with patch("app.services.llm_service.semantic_cache") as mock_cache, \
             patch("app.services.llm_service.cs_agent") as mock_agent:
            mock_cache.lookup.return_value = ("缓存的回答", [0.1] * 1024)

            chunks = run_async_gen(ChatService.get_chat_response_generator("招牌菜"))

            mock_agent.astream_events.assert_not_called()
            assert "".join(c for c in chunks if '"content"' in c)
            assert chunks[-1] == "data: [DONE]\n\n"

    def test_pure_knowledge_answer_is_cached(self):
        events = [stream_event("招牌是"), stream_event("黑叉烧")]
        with patch("app.services.llm_service.semantic_cache") as mock_cache, \
             patch("app.services.llm_service.cs_agent") as mock_agent:
            mock_cache.lookup.return_value = (None, [0.1] * 1024)
            mock_agent.astream_events.side_effect = fake_agent_events(events)

            chunks = run_async_gen(ChatService.get_chat_response_generator("招牌菜"))

            mock_cache.store.assert_called_once_with(
                "招牌菜", "招牌是黑叉烧", question_vector=[0.1] * 1024
            )
            assert chunks[-1] == "data: [DONE]\n\n"

    def test_dynamic_tool_answer_not_cached(self):
        events = [
            tool_start_event("check_dish_inventory"),
            stream_event("黑叉烧今日还剩 5 份"),
        ]
        with patch("app.services.llm_service.semantic_cache") as mock_cache, \
             patch("app.services.llm_service.cs_agent") as mock_agent:
            mock_cache.lookup.return_value = (None, None)
            mock_agent.astream_events.side_effect = fake_agent_events(events)

            run_async_gen(ChatService.get_chat_response_generator("黑叉烧还有吗"))

            mock_cache.store.assert_not_called()

    def test_rag_recommendation_answer_is_cached(self):
        # search_dish_by_preference 查的是知识库（非实时），回答可缓存；
        # 知识库更新由 kb_version 指纹兜底失效
        events = [
            tool_start_event("search_dish_by_preference"),
            stream_event("推荐您试试水煮鱼"),
        ]
        with patch("app.services.llm_service.semantic_cache") as mock_cache, \
             patch("app.services.llm_service.cs_agent") as mock_agent:
            mock_cache.lookup.return_value = (None, None)
            mock_agent.astream_events.side_effect = fake_agent_events(events)

            run_async_gen(ChatService.get_chat_response_generator("推荐一个辣菜"))

            mock_cache.store.assert_called_once()
