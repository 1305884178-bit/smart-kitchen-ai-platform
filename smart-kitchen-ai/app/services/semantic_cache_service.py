import hashlib
import json
import logging
import time

from pymilvus import MilvusClient

from app.config import settings
from app.db.milvus_client import MILVUS_DB_PATH, ensure_cache_collection
from app.db.mysql_client import get_db_connection
from app.db.redis_client import redis_client
from app.services.rag_service import embeddings

logger = logging.getLogger(__name__)

KB_VERSION_CACHE_KEY = "kb_version_fingerprint"
KB_VERSION_FALLBACK = "unknown"


class SemanticCacheService:
    """
    AI 客服语义缓存：问题先转 embedding，与 Milvus 中历史问题向量算余弦相似度，
    >= 阈值（默认 0.92）即命中，避免近义问法反复调用 LLM。

    与原 MD5 精确匹配缓存的差异：
    - 命中维度从"逐字相同"放宽到"语义近似"，差字/标点/语序不影响命中；
    - 每条缓存带知识库版本指纹，知识库变更后旧缓存自动失效，不会回答已下线内容；
    - 仅写入非动态回答（由调用方保证：含库存/配料等实时工具调用的回答不入缓存）。

    所有外部调用（embedding / Milvus / Redis / MySQL）异常均降级为"不缓存"，
    绝不影响聊天主流程。
    """

    def __init__(self):
        self._client = None

    def _get_client(self) -> MilvusClient:
        if self._client is None:
            client = MilvusClient(MILVUS_DB_PATH)
            # 不存在时按显式 schema 创建（含 kb_version 标量索引）；已存在则沿用
            ensure_cache_collection(client, settings.semantic_cache_collection)
            self._client = client
        return self._client

    def get_kb_version(self) -> str:
        """
        知识库版本指纹：对 ai_knowledge_document 全表 (id, version, status) 取哈希，
        文档新增/版本/状态变化都会改变指纹，使旧缓存条目因 filter 不匹配而自然失效。
        指纹在 Redis 缓存 60s；知识库元数据写入成功后 Java 侧会主动 DEL 该 key，
        TTL 仅作兜底。MySQL 不可用时降级为固定值 "unknown"（缓存仍可用，
        仅暂时失去知识库变更感知能力）。
        """
        try:
            cached = redis_client.get(KB_VERSION_CACHE_KEY)
            if cached:
                return cached
        except Exception as e:
            logger.warning(f"[SemanticCache] 读取指纹缓存失败，直接查库: {e}")

        try:
            conn = get_db_connection()
            try:
                with conn.cursor() as cursor:
                    cursor.execute(
                        "SELECT id, version, status FROM ai_knowledge_document ORDER BY id"
                    )
                    rows = cursor.fetchall()
            finally:
                conn.close()
            payload = json.dumps(rows, sort_keys=True, default=str)
            fingerprint = hashlib.md5(payload.encode("utf-8")).hexdigest()[:16]
        except Exception as e:
            logger.warning(f"[SemanticCache] 计算知识库指纹失败，降级为 {KB_VERSION_FALLBACK}: {e}")
            return KB_VERSION_FALLBACK

        try:
            redis_client.setex(KB_VERSION_CACHE_KEY, settings.kb_version_cache_ttl, fingerprint)
        except Exception as e:
            logger.warning(f"[SemanticCache] 写入指纹缓存失败: {e}")
        return fingerprint

    def invalidate_kb_version_cache(self) -> None:
        """主动删除指纹缓存，使下次 get_kb_version 重新查库计算。保留 TTL 兜底。"""
        try:
            redis_client.delete(KB_VERSION_CACHE_KEY)
        except Exception as e:
            logger.warning(f"[SemanticCache] 删除指纹缓存失败: {e}")

    def lookup(self, message: str) -> tuple[str | None, list[float] | None]:
        """
        语义查找历史回答。

        Returns:
            (命中的回答或 None, 问题向量或 None)。问题向量随结果返回，
            供未命中后写缓存时复用，避免对同一问题重复调用 embedding。
        """
        try:
            question_vector = embeddings.embed_query(message)
            client = self._get_client()
            min_created_at = int(time.time()) - settings.semantic_cache_ttl_days * 86400
            filter_expr = (
                f'kb_version == "{self.get_kb_version()}" '
                f'and created_at >= {min_created_at}'
            )
            search_res = client.search(
                collection_name=settings.semantic_cache_collection,
                data=[question_vector],
                limit=1,
                filter=filter_expr,
                output_fields=["answer", "question"]
            )
            hits = search_res[0] if search_res else []
            # COSINE metric 的 distance 即余弦相似度，越大越相似
            if hits and hits[0].get("distance", 0) >= settings.semantic_cache_threshold:
                answer = hits[0].get("entity", {}).get("answer")
                if answer:
                    logger.info(
                        f"[SemanticCache] 命中 (score={hits[0]['distance']:.4f}, "
                        f"阈值={settings.semantic_cache_threshold}): {message[:30]}..."
                    )
                    return answer, question_vector
            return None, question_vector
        except Exception as e:
            logger.warning(f"[SemanticCache] 查询失败，降级为不缓存: {e}")
            return None, None

    def store(self, message: str, answer: str, question_vector: list[float] | None = None):
        """
        写入语义缓存。仅应由调用方在确认为非动态回答（未调用实时数据工具）后调用。
        question_vector 未提供时现场计算。
        """
        try:
            if question_vector is None:
                question_vector = embeddings.embed_query(message)
            client = self._get_client()
            client.insert(
                collection_name=settings.semantic_cache_collection,
                data=[{
                    "vector": question_vector,
                    "question": message,
                    "answer": answer,
                    "kb_version": self.get_kb_version(),
                    "created_at": int(time.time())
                }]
            )
        except Exception as e:
            logger.warning(f"[SemanticCache] 写入失败，跳过缓存: {e}")


semantic_cache = SemanticCacheService()
