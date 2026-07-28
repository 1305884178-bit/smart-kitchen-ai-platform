from pymilvus import MilvusClient
import os

MILVUS_DB_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(__file__))), "data", "milvus.db")
COLLECTION_NAME = "kitchen_knowledge"
DIMENSION = 1024  # 阿里云百炼 text-embedding-v4 维度

def get_milvus_client():
    """
    获取 Milvus 客户端实例。如果集合不存在则自动创建。
    
    Returns:
        MilvusClient: Milvus 客户端实例
    """
    client = MilvusClient(MILVUS_DB_PATH)
    
    if not client.has_collection(COLLECTION_NAME):
        client.create_collection(
            collection_name=COLLECTION_NAME,
            dimension=DIMENSION,
            auto_id=True,  # Automatically generate IDs
            enable_dynamic_field=True  # For metadata
        )
    client.load_collection(COLLECTION_NAME)
    return client
