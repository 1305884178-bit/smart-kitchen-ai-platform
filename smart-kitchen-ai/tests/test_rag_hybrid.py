"""混合检索与可选 Reranker 测试；所有外部调用均 mock，可离线运行。"""
from unittest.mock import MagicMock, patch

from app.config import settings
from app.services import rag_service
from app.services.reranker_service import RerankerService


def _row(item_id: int, title: str, text: str) -> dict:
    return {
        "id": item_id,
        "title": title,
        "text": text,
        "document_id": str(item_id),
        "chunk_index": 0,
        "version": "v1.0",
    }


class TestBm25AndRrf:
    def test_bm25_recalls_exact_dish_entity(self):
        rows = [
            _row(1, "椰子乌鸡汤", "椰子乌鸡汤 椰香清甜，适合秋冬进补"),
            _row(2, "玉米萝卜排骨汤", "玉米萝卜排骨汤 清淡鲜甜"),
        ]
        results = rag_service._bm25_rank("椰子乌鸡汤口味怎么样", rows, limit=2)

        assert results[0]["title"] == "椰子乌鸡汤"
        assert results[0]["bm25_score"] > 0

    def test_rrf_fuses_independent_dense_and_bm25_rankings(self):
        dense = [dict(_row(1, "A", "菜 A"), distance=0.80), dict(_row(2, "B", "菜 B"), distance=0.79)]
        bm25 = [dict(_row(2, "B", "菜 B"), bm25_score=4.2), dict(_row(3, "C", "菜 C"), bm25_score=3.1)]

        results = rag_service._rrf_fuse(dense, bm25, rrf_k=60)
        by_title = {item["title"]: item for item in results}

        assert by_title["B"]["rrf_score"] > by_title["A"]["rrf_score"]
        assert set(by_title["B"]["retrieval_sources"]) == {"dense", "bm25"}


class TestHybridSearchFallback:
    def test_unconfigured_reranker_returns_rrf_results_without_network(self):
        client = MagicMock()
        client.search.return_value = [[
            {"id": 1, "distance": 0.78, "entity": _row(1, "黑叉烧", "黑叉烧 蜜汁甜咸 招牌菜")},
        ]]
        client.query.return_value = [_row(1, "黑叉烧", "黑叉烧 蜜汁甜咸 招牌菜")]

        embedding_service = MagicMock()
        embedding_service.embed_query.return_value = [0.1] * 1024
        with patch.object(rag_service, "get_milvus_client", return_value=client), \
             patch.object(rag_service, "embeddings", embedding_service), \
             patch.object(settings, "reranker_enabled", False), \
             patch.object(settings, "rag_score_threshold", 0.55):
            results = rag_service.search_knowledge("黑叉烧甜吗", top_k=3)

        assert len(results) == 1
        assert results[0]["title"] == "黑叉烧"
        assert results[0]["retrieval_strategy"] == "rrf_fallback"
        assert client.query.call_args.kwargs["filter"] == client.search.call_args.kwargs["filter"]

    def test_reranker_result_replaces_rrf_order_when_available(self):
        client = MagicMock()
        client.search.return_value = [[
            {"id": 1, "distance": 0.82, "entity": _row(1, "黑叉烧", "黑叉烧 蜜汁甜咸")},
            {"id": 2, "distance": 0.80, "entity": _row(2, "五柳炸蛋", "五柳炸蛋 酸甜开胃")},
        ]]
        client.query.return_value = [
            _row(1, "黑叉烧", "黑叉烧 蜜汁甜咸"),
            _row(2, "五柳炸蛋", "五柳炸蛋 酸甜开胃"),
        ]

        def reverse_candidates(_query, candidates, top_k):
            return [dict(candidates[1], rerank_score=0.99), dict(candidates[0], rerank_score=0.50)][:top_k]

        embedding_service = MagicMock()
        embedding_service.embed_query.return_value = [0.1] * 1024
        with patch.object(rag_service, "get_milvus_client", return_value=client), \
             patch.object(rag_service, "embeddings", embedding_service), \
             patch.object(rag_service.reranker_service, "rerank", side_effect=reverse_candidates), \
             patch.object(settings, "rag_score_threshold", 0.55):
            results = rag_service.search_knowledge("甜口下饭菜", top_k=2)

        assert [item["title"] for item in results] == ["五柳炸蛋", "黑叉烧"]
        assert {item["retrieval_strategy"] for item in results} == {"reranker"}


class TestCloudRerankerProtocol:
    def test_missing_configuration_never_calls_http(self):
        service = RerankerService()
        with patch.object(settings, "reranker_enabled", False), \
             patch("app.services.reranker_service.httpx.post") as post:
            assert service.rerank("问题", [_row(1, "A", "内容")], top_k=1) is None
        post.assert_not_called()

    def test_cloud_response_is_mapped_back_to_candidates(self):
        response = MagicMock()
        response.json.return_value = {"results": [{"index": 1, "relevance_score": 0.97}, {"index": 0, "relevance_score": 0.45}]}
        response.raise_for_status.return_value = None
        service = RerankerService()
        candidates = [_row(1, "A", "内容 A"), _row(2, "B", "内容 B")]

        with patch.object(settings, "reranker_enabled", True), \
             patch.object(settings, "reranker_api_url", "https://rerank.example/v1/rerank"), \
             patch.object(settings, "reranker_api_key", "test-key"), \
             patch.object(settings, "reranker_model", "test-reranker"), \
             patch("app.services.reranker_service.httpx.post", return_value=response) as post:
            results = service.rerank("问题", candidates, top_k=2)

        assert [item["title"] for item in results] == ["B", "A"]
        assert results[0]["rerank_score"] == 0.97
        assert post.call_args.kwargs["json"]["documents"] == ["内容 A", "内容 B"]
