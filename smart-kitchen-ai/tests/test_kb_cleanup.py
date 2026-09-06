"""
知识库归档清理任务测试（MySQL/Milvus 全部 mock，离线可跑）。

核心约定（对应需求 P0-5.1）：
- status=archived 且 update_time 早于 N 天前 → 删除 Milvus 残留向量（元数据保留审计）
- 边界：刚 archived 不删，超过 N 天才删（SQL 用 update_time < cutoff 严格小于，恰好 N 天不删）
- active/processing 文档即使很老也不许被删（不用 Milvus TTL 替代本任务）
- MySQL 已无 active/processing 记录但 Milvus 仍在的孤儿向量 → 删除
- 任务失败只打日志，不得拖垮服务
"""
from datetime import datetime, timedelta
from unittest.mock import MagicMock, patch

import pytest

import app.services.kb_cleanup_service as cleanup


def fake_conn(rows):
    """构造返回指定行的假 MySQL 连接（cursor 上下文管理器）。"""
    cursor = MagicMock()
    cursor.fetchall.return_value = rows
    conn = MagicMock()
    ctx = MagicMock()
    ctx.__enter__ = MagicMock(return_value=cursor)
    ctx.__exit__ = MagicMock(return_value=False)
    conn.cursor.return_value = ctx
    return conn, cursor


NOW = datetime(2026, 9, 6, 3, 30)


class TestArchivedCleanup:
    def test_expired_archived_vectors_deleted(self):
        rows = [
            {"id": 1, "title": "旧版菜单", "update_time": NOW - timedelta(days=10)},
            {"id": 2, "title": "旧版公告", "update_time": NOW - timedelta(days=8)},
        ]
        conn, _ = fake_conn(rows)
        with patch.object(cleanup, "get_db_connection", return_value=conn), \
             patch.object(cleanup.rag_service, "delete_chunks_by_document_id", return_value=3) as mock_del:
            deleted = cleanup.cleanup_archived_vectors(retention_days=7, now=NOW)

        assert deleted == ["1", "2"]
        assert mock_del.call_count == 2
        mock_del.assert_any_call("1")
        mock_del.assert_any_call("2")

    def test_recently_archived_not_deleted(self):
        # 刚 archived（DB 按 update_time < cutoff 过滤后返回空）→ 不删
        conn, cursor = fake_conn([])
        with patch.object(cleanup, "get_db_connection", return_value=conn), \
             patch.object(cleanup.rag_service, "delete_chunks_by_document_id") as mock_del:
            deleted = cleanup.cleanup_archived_vectors(retention_days=7, now=NOW)

        assert deleted == []
        mock_del.assert_not_called()
        # 边界语义：SQL 为 update_time < now - N 天（严格小于），恰好 N 天不删
        sql, params = cursor.execute.call_args.args
        assert "status = 'archived'" in sql
        assert "update_time < %s" in sql
        assert params[0] == NOW - timedelta(days=7)

    def test_only_archived_status_queried(self):
        # active 文档即使很老也不许被这条任务删掉：SQL 只查 archived
        conn, cursor = fake_conn([])
        with patch.object(cleanup, "get_db_connection", return_value=conn):
            cleanup.cleanup_archived_vectors(retention_days=7, now=NOW)
        sql = cursor.execute.call_args.args[0]
        assert "status = 'archived'" in sql
        assert "active" not in sql.replace("'archived'", "")

    def test_delete_failure_does_not_block_others(self):
        rows = [
            {"id": 1, "title": "A", "update_time": NOW - timedelta(days=10)},
            {"id": 2, "title": "B", "update_time": NOW - timedelta(days=10)},
        ]
        conn, _ = fake_conn(rows)

        def delete_side_effect(doc_id):
            if doc_id == "1":
                raise RuntimeError("Milvus 连接失败")
            return 2

        with patch.object(cleanup, "get_db_connection", return_value=conn), \
             patch.object(cleanup.rag_service, "delete_chunks_by_document_id",
                          side_effect=delete_side_effect):
            deleted = cleanup.cleanup_archived_vectors(retention_days=7, now=NOW)

        assert deleted == ["2"]


class TestOrphanCleanup:
    def test_orphan_vectors_deleted(self):
        live_conn, _ = fake_conn([{"id": 1}, {"id": 2}])
        with patch.object(cleanup, "get_db_connection", return_value=live_conn), \
             patch.object(cleanup.rag_service, "list_all_document_ids", return_value={"1", "2", "99"}), \
             patch.object(cleanup.rag_service, "delete_chunks_by_document_id", return_value=1) as mock_del:
            deleted = cleanup.cleanup_orphan_vectors()

        # MySQL 无 active/processing 记录的 99 被删，存活文档不受影响
        assert deleted == ["99"]
        mock_del.assert_called_once_with("99")

    def test_no_orphans(self):
        live_conn, _ = fake_conn([{"id": 1}])
        with patch.object(cleanup, "get_db_connection", return_value=live_conn), \
             patch.object(cleanup.rag_service, "list_all_document_ids", return_value={"1"}), \
             patch.object(cleanup.rag_service, "delete_chunks_by_document_id") as mock_del:
            assert cleanup.cleanup_orphan_vectors() == []
        mock_del.assert_not_called()

    def test_mysql_down_skips_orphan_cleanup(self):
        # MySQL 不可用时无法判定孤儿，宁可不删
        with patch.object(cleanup, "get_db_connection", side_effect=RuntimeError("连接失败")), \
             patch.object(cleanup.rag_service, "delete_chunks_by_document_id") as mock_del:
            assert cleanup.cleanup_orphan_vectors() == []
        mock_del.assert_not_called()

    def test_milvus_read_failure_skips_orphan_cleanup(self):
        live_conn, _ = fake_conn([{"id": 1}])
        with patch.object(cleanup, "get_db_connection", return_value=live_conn), \
             patch.object(cleanup.rag_service, "list_all_document_ids",
                          side_effect=RuntimeError("Milvus 故障")), \
             patch.object(cleanup.rag_service, "delete_chunks_by_document_id") as mock_del:
            assert cleanup.cleanup_orphan_vectors() == []
        mock_del.assert_not_called()


class TestRunCleanupJob:
    def test_job_failure_only_logs(self):
        # 任务失败只打日志，不得拖垮 AI 服务
        with patch.object(cleanup, "cleanup_archived_vectors",
                          side_effect=RuntimeError("炸了")):
            cleanup.run_cleanup_job()  # 不应抛异常

    def test_job_runs_both_cleanups(self):
        with patch.object(cleanup, "cleanup_archived_vectors", return_value=["1"]) as m1, \
             patch.object(cleanup, "cleanup_orphan_vectors", return_value=["2"]) as m2:
            cleanup.run_cleanup_job()
        m1.assert_called_once()
        m2.assert_called_once()
