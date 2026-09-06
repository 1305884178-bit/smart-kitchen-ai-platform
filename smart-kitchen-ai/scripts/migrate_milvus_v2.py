#!/usr/bin/env python3
"""
Milvus 数据迁移（v2）：

旧版 process_and_store_document 会把 effective_from=None 写成 null，
而新版默认过滤表达式 `effective_from == "" or effective_from <= now` 匹配不到 null，
导致旧 chunk 对检索不可见。本脚本把这些行的 effective_from 重写为 ""（空串）。

做法：整行读出（含向量）→ 按 id 删除 → 以原字段重插（id 会变，无外部引用，安全）。
幂等：没有 null 行时什么都不做。

用法：
    cd smart-kitchen-ai
    .venv/bin/python scripts/migrate_milvus_v2.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from pymilvus import MilvusClient
from app.db.milvus_client import MILVUS_DB_PATH, COLLECTION_NAME

BATCH = 500


def migrate():
    client = MilvusClient(MILVUS_DB_PATH)
    if not client.has_collection(COLLECTION_NAME):
        print(f"集合 {COLLECTION_NAME} 不存在，无需迁移")
        return
    client.load_collection(COLLECTION_NAME)

    rows = client.query(
        collection_name=COLLECTION_NAME,
        filter="id > 0",
        output_fields=["id", "vector", "document_id", "chunk_index", "text",
                       "version", "status", "effective_from", "title"],
        limit=16384,
    )
    legacy = [r for r in rows if r.get("effective_from") is None]
    print(f"共 {len(rows)} 行，其中 effective_from 为 null 的旧数据 {len(legacy)} 行")
    if not legacy:
        print("无需迁移")
        return

    for r in legacy:
        r["effective_from"] = ""
        r.pop("id", None)

    ids = [r["id"] for r in rows if r.get("effective_from") is None]
    for i in range(0, len(ids), BATCH):
        client.delete(collection_name=COLLECTION_NAME, ids=ids[i:i + BATCH])
    for i in range(0, len(legacy), BATCH):
        client.insert(collection_name=COLLECTION_NAME, data=legacy[i:i + BATCH])
    client.flush(COLLECTION_NAME)
    print(f"已迁移 {len(legacy)} 行（effective_from: null -> \"\"）")


if __name__ == "__main__":
    migrate()
