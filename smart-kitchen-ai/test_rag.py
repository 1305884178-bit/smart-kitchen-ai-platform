"""
RAG 检索单元测试（外部依赖全部 mock，离线可跑）：
- 默认过滤：status == "active" 且 (effective_from 为空或 <= 当前时间)；仅显式传 version 才追加版本条件
- RAG_SCORE_THRESHOLD：低于阈值的 hit 丢弃；全部低于阈值返回空（不把噪声 top3 塞给模型）
- 结果带 distance / document_id / chunk_index / title（评测与溯源）
- 混合检索：菜名/口味关键词命中加权影响排序
- delete_chunks_by_document_id / effective_from 归一化 / embedding 批量与降级

运行：.venv/bin/python -m pytest test_rag.py
"""
from datetime import datetime
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest

import app.services.rag_service as rag
from app.config import settings


def make_client(hits):
    client = MagicMock()
    client.search.return_value = [hits]
    return client


def hit(distance, text="文本", document_id="1", chunk_index=0, title="菜品", version="v2.0"):
    return {
        "id": 1,
        "distance": distance,
        "entity": {
            "text": text,
            "document_id": document_id,
            "chunk_index": chunk_index,
            "title": title,
            "version": version,
            "status": "active",
            "effective_from": "",
        },
    }


@pytest.fixture
def mocks():
    with patch.object(rag, "get_milvus_client") as get_client, \
         patch.object(rag, "embeddings") as mock_emb, \
         patch.object(rag, "get_dish_names", return_value=[]):
        mock_emb.embed_query.return_value = [0.1] * 1024
        yield SimpleNamespace(get_client=get_client, embeddings=mock_emb)


# ---------- 默认过滤 ----------

class TestDefaultFilter:
    def test_default_filter_active_and_effective(self, mocks):
        client = make_client([])
        mocks.get_client.return_value = client

        rag.search_knowledge("任意问题")

        filter_expr = client.search.call_args.kwargs["filter"]
        assert 'status == "active"' in filter_expr
        assert 'effective_from == ""' in filter_expr
        assert "effective_from <=" in filter_expr
        # 未传 version 时不得带版本条件（C 端只看 active + 已生效）
        assert "version ==" not in filter_expr

    def test_version_filter_only_when_explicit(self, mocks):
        client = make_client([])
        mocks.get_client.return_value = client

        rag.search_knowledge("任意问题", version="v2.0")

        filter_expr = client.search.call_args.kwargs["filter"]
        assert 'version == "v2.0"' in filter_expr
        # 默认条件仍在
        assert 'status == "active"' in filter_expr

    def test_version_escaped_against_filter_injection(self, mocks):
        client = make_client([])
        mocks.get_client.return_value = client

        rag.search_knowledge("q", version='v2" or status == "draft')

        filter_expr = client.search.call_args.kwargs["filter"]
        # 注入的引号被转义，整个 version 值仍是一个字符串字面量，无法逃逸出表达式
        assert 'version == "v2\\" or status == \\"draft"' in filter_expr


# ---------- 相似度阈值 ----------

class TestScoreThreshold:
    def test_low_distance_hits_dropped(self, mocks):
        hits = [hit(0.7, "高分A"), hit(0.4, "低分"), hit(0.9, "高分B")]
        mocks.get_client.return_value = make_client(hits)
        with patch.object(settings, "rag_score_threshold", 0.55):
            results = rag.search_knowledge("q")

        assert {r["text"] for r in results} == {"高分A", "高分B"}
        # 按 score 降序（无关键词命中时 score == distance）
        assert [r["distance"] for r in results] == [0.9, 0.7]

    def test_all_below_threshold_returns_empty(self, mocks):
        # 低相关 query：一条都不过阈值时返回空，走拒答话术，而不是塞 3 条噪声
        hits = [hit(0.31, "噪声1"), hit(0.28, "噪声2"), hit(0.25, "噪声3")]
        mocks.get_client.return_value = make_client(hits)
        with patch.object(settings, "rag_score_threshold", 0.55):
            results = rag.search_knowledge("今天天气怎么样")

        assert results == []

    def test_results_carry_distance_and_provenance(self, mocks):
        mocks.get_client.return_value = make_client([hit(0.9, "正文", document_id="42", chunk_index=1, title="黑叉烧")])
        with patch.object(settings, "rag_score_threshold", 0.55):
            results = rag.search_knowledge("q")

        r = results[0]
        assert r["distance"] == 0.9
        assert r["document_id"] == "42"
        assert r["chunk_index"] == 1
        assert r["title"] == "黑叉烧"


# ---------- 混合检索 ----------

class TestHybridRerank:
    def test_keyword_hit_changes_order(self, mocks):
        # B 向量分更高，但 A 命中 query 中的菜名与口味词，加权后 A 排前
        hits = [
            hit(0.60, "蜜汁浓郁带焦糖香，招牌下饭菜", document_id="1", title="黑叉烧"),
            hit(0.62, "汤口清亮鲜甜，温润不油腻", document_id="2", title="玉米萝卜排骨汤"),
        ]
        mocks.get_client.return_value = make_client(hits)
        with patch.object(rag, "get_dish_names", return_value=["黑叉烧"]), \
             patch.object(settings, "rag_score_threshold", 0.55):
            results = rag.search_knowledge("黑叉烧是招牌菜吗")

        assert results[0]["title"] == "黑叉烧"
        assert results[0]["score"] > results[1]["score"]

    def test_vector_recall_widened_then_cut_to_top_k(self, mocks):
        # 召回数应大于 top_k（放大召回留给重排空间），最终截到 top_k
        hits = [hit(0.9 - i * 0.01, f"文{i}") for i in range(10)]
        client = make_client(hits)
        mocks.get_client.return_value = client

        with patch.object(settings, "rag_score_threshold", 0.0):
            results = rag.search_knowledge("q", top_k=3)

        assert client.search.call_args.kwargs["limit"] >= 8
        assert len(results) == 3


# ---------- 删除与写入 ----------

class TestDeleteAndStore:
    def test_delete_chunks_by_document_id(self, mocks):
        client = MagicMock()
        client.delete.return_value = [1, 2, 3]
        mocks.get_client.return_value = client

        count = rag.delete_chunks_by_document_id("42")

        assert count == 3
        assert client.delete.call_args.kwargs["filter"] == 'document_id == "42"'

    def test_process_uses_given_document_id_and_normalizes_effective_from(self, mocks):
        client = MagicMock()
        mocks.get_client.return_value = client
        mocks.embeddings.embed_documents.return_value = [[0.1] * 1024, [0.2] * 1024]

        with patch.object(rag.text_splitter, "split_text", return_value=["块1", "块2"]):
            document_id, chunk_count = rag.process_and_store_document(
                content="任意", metadata={"title": "黑叉烧"}, version="v2.0",
                status="active", effective_from=None, document_id="100",
            )

        assert document_id == "100"
        assert chunk_count == 2
        rows = client.insert.call_args.kwargs["data"]
        # effective_from 归一化为空串（null 无法被 filter 匹配）
        assert all(r["effective_from"] == "" for r in rows)
        assert all(r["document_id"] == "100" for r in rows)
        assert rows[0]["title"] == "黑叉烧"
        assert rows[1]["chunk_index"] == 1

    def test_normalize_effective_from(self):
        assert rag._normalize_effective_from(None) == ""
        assert rag._normalize_effective_from("") == ""
        assert rag._normalize_effective_from(datetime(2026, 8, 16)) == "2026-08-16T00:00:00"
        assert rag._normalize_effective_from("2026-08-16") == "2026-08-16"


# ---------- Embedding 批量 ----------

class TestEmbeddingBatch:
    def make_embeddings(self):
        emb = rag.DashScopeEmbeddings(api_key="k", base_url="http://localhost", model="m")
        emb.client = MagicMock()
        return emb

    def test_embed_documents_single_batch_request(self):
        emb = self.make_embeddings()
        resp = MagicMock()
        resp.data = [SimpleNamespace(index=0, embedding=[0.1]), SimpleNamespace(index=1, embedding=[0.2])]
        emb.client.create.return_value = resp

        vectors = emb.embed_documents(["甲", "乙"])

        # 一次请求多条，不按单条循环
        assert emb.client.create.call_count == 1
        assert emb.client.create.call_args.kwargs["input"] == ["甲", "乙"]
        assert vectors == [[0.1], [0.2]]

    def test_embed_documents_fallback_to_per_text_on_batch_failure(self):
        emb = self.make_embeddings()

        def create(input, model):
            if isinstance(input, list):
                raise RuntimeError("批量接口不兼容")
            resp = MagicMock()
            resp.data = [SimpleNamespace(index=0, embedding=[0.5])]
            return resp

        emb.client.create.side_effect = create

        vectors = emb.embed_documents(["甲", "乙"])

        assert emb.client.create.call_count == 3  # 1 次批量失败 + 2 次逐条
        assert vectors == [[0.5], [0.5]]

    def test_embed_documents_empty(self):
        emb = self.make_embeddings()
        assert emb.embed_documents([]) == []
        emb.client.create.assert_not_called()


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
