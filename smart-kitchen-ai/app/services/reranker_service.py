"""可选云端 Reranker：接口不可用时返回 None，由调用方无损降级到 RRF 结果。"""
import logging
from typing import Any

import httpx

from app.config import settings

logger = logging.getLogger(__name__)


class RerankerService:
    """兼容常见 ``query + documents + top_n`` JSON 协议的云端重排客户端。"""

    @staticmethod
    def available() -> bool:
        return bool(
            settings.reranker_enabled
            and settings.reranker_api_url
            and settings.reranker_api_key
        )

    def rerank(self, query: str, candidates: list[dict[str, Any]], top_k: int) -> list[dict[str, Any]] | None:
        """返回按云端相关度排序的候选；任何不可用/异常均返回 None。"""
        if not candidates:
            return []
        if not self.available():
            return None

        payload = {
            "model": settings.reranker_model,
            "query": query,
            "documents": [item.get("text") or "" for item in candidates],
            "top_n": min(max(top_k, 1), len(candidates)),
        }
        headers = {
            "Authorization": f"Bearer {settings.reranker_api_key}",
            "Content-Type": "application/json",
        }
        try:
            response = httpx.post(
                settings.reranker_api_url,
                json=payload,
                headers=headers,
                timeout=settings.reranker_timeout_seconds,
            )
            response.raise_for_status()
            body = response.json()
            raw_results = body.get("results", body.get("data", [])) if isinstance(body, dict) else body
            if not isinstance(raw_results, list):
                raise ValueError("Reranker 响应缺少 results 列表")

            ranked: list[dict[str, Any]] = []
            seen_indices: set[int] = set()
            for item in raw_results:
                if not isinstance(item, dict):
                    continue
                index = item.get("index")
                if not isinstance(index, int) or index < 0 or index >= len(candidates) or index in seen_indices:
                    continue
                seen_indices.add(index)
                candidate = dict(candidates[index])
                score = item.get("relevance_score", item.get("score"))
                if isinstance(score, (int, float)):
                    candidate["rerank_score"] = float(score)
                ranked.append(candidate)

            if not ranked:
                raise ValueError("Reranker 未返回有效候选下标")
            ranked.sort(key=lambda item: item.get("rerank_score", float("-inf")), reverse=True)
            return ranked[:top_k]
        except Exception as exc:
            logger.warning("[Reranker] 调用失败，降级为 RRF 结果: %s", exc)
            return None


reranker_service = RerankerService()
