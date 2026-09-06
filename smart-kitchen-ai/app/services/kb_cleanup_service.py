"""
知识库向量清理（替代 TTL 的显式生命周期管理）：

1. 归档超期清理：MySQL 中 status=archived 且更新时间早于 N 天（KB_ARCHIVED_RETENTION_DAYS，
   默认 7）的文档，按 document_id 删除 Milvus kitchen_knowledge 中残留 chunk；
   MySQL 元数据保留便于审计，向量必须删。
2. 孤儿向量清理：MySQL 已无对应 active/processing 记录、但 Milvus 仍在的 document_id，
   删除其全部 chunk（补偿上传/归档流程中漏删的向量）。

约束：
- active/processing 文档即使很老也永不被本任务删除；不使用 Milvus TTL；
- 任务失败只打日志，不得拖垮 AI 服务启动。
"""
import logging
from datetime import datetime, timedelta

from app.config import settings
from app.db.mysql_client import get_db_connection
from app.services import rag_service

logger = logging.getLogger(__name__)


def _fetch_archived_expired(retention_days: int, now: datetime | None = None) -> list[dict]:
    """status=archived 且 update_time 早于 now - N 天的文档。"""
    now = now or datetime.now()
    cutoff = now - timedelta(days=retention_days)
    conn = get_db_connection()
    try:
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT id, title, update_time FROM ai_knowledge_document "
                "WHERE status = 'archived' AND update_time < %s",
                (cutoff,),
            )
            return cursor.fetchall()
    finally:
        conn.close()


def _fetch_live_document_ids() -> set[str]:
    """MySQL 中 active/processing 文档的 id 集合（字符串，与 Milvus document_id 对齐）。"""
    conn = get_db_connection()
    try:
        with conn.cursor() as cursor:
            cursor.execute(
                "SELECT id FROM ai_knowledge_document WHERE status IN ('active', 'processing')"
            )
            return {str(r["id"]) for r in cursor.fetchall()}
    finally:
        conn.close()


def cleanup_archived_vectors(retention_days: int | None = None,
                             now: datetime | None = None) -> list[str]:
    """删除归档超过 N 天的文档向量，返回已删除的 document_id 列表。"""
    days = retention_days if retention_days is not None else settings.kb_archived_retention_days
    deleted = []
    for row in _fetch_archived_expired(days, now):
        doc_id = str(row["id"])
        try:
            count = rag_service.delete_chunks_by_document_id(doc_id)
            if count:
                deleted.append(doc_id)
                logger.info(
                    f"[KbCleanup] 归档超期删除向量: document_id={doc_id} "
                    f"title={row.get('title')} chunks={count}"
                )
        except Exception as e:
            logger.warning(f"[KbCleanup] 删除归档文档向量失败 document_id={doc_id}: {e}")
    return deleted


def cleanup_orphan_vectors() -> list[str]:
    """删除 MySQL 中无 active/processing 对应记录的孤儿向量，返回已删除的 document_id 列表。"""
    try:
        live_ids = _fetch_live_document_ids()
    except Exception as e:
        # MySQL 不可用时无法判定孤儿，宁可不删
        logger.warning(f"[KbCleanup] 查询 MySQL 存活文档失败，跳过孤儿清理: {e}")
        return []
    try:
        existing_ids = rag_service.list_all_document_ids()
    except Exception as e:
        logger.warning(f"[KbCleanup] 读取 Milvus document_id 失败，跳过孤儿清理: {e}")
        return []

    orphans = existing_ids - live_ids
    deleted = []
    for doc_id in sorted(orphans):
        try:
            count = rag_service.delete_chunks_by_document_id(doc_id)
            if count:
                deleted.append(doc_id)
                logger.info(f"[KbCleanup] 删除孤儿向量: document_id={doc_id} chunks={count}")
        except Exception as e:
            logger.warning(f"[KbCleanup] 删除孤儿向量失败 document_id={doc_id}: {e}")
    return deleted


def run_cleanup_job(now: datetime | None = None):
    """定时任务入口：任何异常只打日志，不影响服务运行。"""
    try:
        archived_deleted = cleanup_archived_vectors(now=now)
        orphan_deleted = cleanup_orphan_vectors()
        logger.info(
            f"[KbCleanup] 清理完成: 归档超期 {len(archived_deleted)} 个文档, "
            f"孤儿向量 {len(orphan_deleted)} 个文档"
        )
    except Exception as e:
        logger.error(f"[KbCleanup] 清理任务失败（不影响服务）: {e}")
