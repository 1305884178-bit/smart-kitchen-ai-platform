from pymilvus import MilvusClient, DataType
import os
import logging

logger = logging.getLogger(__name__)

# 支持环境变量覆盖，便于评测脚本使用独立临时库，避免污染 data/milvus.db
MILVUS_DB_PATH = os.getenv(
    "MILVUS_DB_PATH",
    os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "data", "milvus.db")
)
COLLECTION_NAME = "kitchen_knowledge"
DIMENSION = 1024  # 阿里云百炼 text-embedding-v4 维度


def _knowledge_schema():
    """kitchen_knowledge 显式 schema：vector + 过滤用到的标量字段。

    enable_dynamic_field=True 保留：其余 metadata（如 taste_tags 等）仍可动态写入。
    """
    schema = MilvusClient.create_schema(auto_id=True, enable_dynamic_field=True)
    schema.add_field("id", DataType.INT64, is_primary=True)
    schema.add_field("vector", DataType.FLOAT_VECTOR, dim=DIMENSION)
    schema.add_field("document_id", DataType.VARCHAR, max_length=64)
    schema.add_field("chunk_index", DataType.INT64)
    schema.add_field("text", DataType.VARCHAR, max_length=4096)
    schema.add_field("title", DataType.VARCHAR, max_length=256)
    schema.add_field("version", DataType.VARCHAR, max_length=32)
    schema.add_field("status", DataType.VARCHAR, max_length=16)
    # 生效时间统一存 ISO 字符串，空串表示无生效约束（禁止存 null，否则 filter 匹配不到）
    schema.add_field("effective_from", DataType.VARCHAR, max_length=40)
    return schema


def _cache_schema():
    """ai_chat_semantic_cache 显式 schema。"""
    schema = MilvusClient.create_schema(auto_id=True, enable_dynamic_field=True)
    schema.add_field("id", DataType.INT64, is_primary=True)
    schema.add_field("vector", DataType.FLOAT_VECTOR, dim=DIMENSION)
    schema.add_field("question", DataType.VARCHAR, max_length=1024)
    schema.add_field("answer", DataType.VARCHAR, max_length=8192)
    schema.add_field("kb_version", DataType.VARCHAR, max_length=64)
    schema.add_field("created_at", DataType.INT64)
    return schema


def _create_with_index(client, collection_name: str, schema, scalar_index_fields=()):
    """创建集合并建索引。

    标量索引使用 INVERTED；Milvus Lite 对标量索引支持有限，若创建失败则
    退化为仅向量索引（filter 表达式仍正确，只是标量过滤走暴力扫描）。
    """
    index_params = client.prepare_index_params()
    index_params.add_index(field_name="vector", index_type="AUTOINDEX", metric_type="COSINE")
    for field in scalar_index_fields:
        index_params.add_index(field_name=field, index_type="INVERTED")
    try:
        client.create_collection(
            collection_name=collection_name, schema=schema, index_params=index_params
        )
    except Exception as e:
        logger.warning(
            f"[Milvus] {collection_name} 标量索引创建失败（Milvus Lite 受限），"
            f"退化为仅向量索引，filter 语义不受影响: {e}"
        )
        client.create_collection(collection_name=collection_name, schema=schema)


def ensure_knowledge_collection(client: MilvusClient):
    """不存在则按显式 schema 创建；已存在（含旧版动态 schema）则直接使用并校验维度。"""
    if not client.has_collection(COLLECTION_NAME):
        _create_with_index(
            client, COLLECTION_NAME, _knowledge_schema(),
            scalar_index_fields=("status", "version", "document_id")
        )
    else:
        _check_vector_dim(client, COLLECTION_NAME)
    client.load_collection(COLLECTION_NAME)


def ensure_cache_collection(client: MilvusClient, collection_name: str):
    if not client.has_collection(collection_name):
        _create_with_index(
            client, collection_name, _cache_schema(),
            scalar_index_fields=("kb_version",)
        )
    else:
        _check_vector_dim(client, collection_name)
    client.load_collection(collection_name)


def _check_vector_dim(client: MilvusClient, collection_name: str):
    """启动期检测：已有集合的向量维度与当前 embedding 模型不一致时给出明确告警，
    避免 silently 写坏库（维度不匹配时 insert/search 才会报错且信息晦涩）。"""
    try:
        desc = client.describe_collection(collection_name)
        for field in desc.get("fields", []):
            if field.get("name") == "vector":
                dim = field.get("params", {}).get("dim")
                if dim is not None and int(dim) != DIMENSION:
                    logger.error(
                        f"[Milvus] {collection_name} 向量维度={dim}，与当前配置 {DIMENSION} 不一致，"
                        f"请备份后删除 data/milvus.db 重建，或改回对应 embedding 模型"
                    )
    except Exception as e:
        logger.warning(f"[Milvus] 校验 {collection_name} schema 失败（不影响使用）: {e}")


def get_milvus_client():
    """
    获取 Milvus 客户端实例。如果集合不存在则按显式 schema 自动创建。

    Returns:
        MilvusClient: Milvus 客户端实例
    """
    client = MilvusClient(MILVUS_DB_PATH)
    ensure_knowledge_collection(client)
    return client
