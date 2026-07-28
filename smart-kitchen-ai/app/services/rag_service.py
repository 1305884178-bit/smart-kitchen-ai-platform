from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_openai import OpenAIEmbeddings
from app.db.milvus_client import get_milvus_client, COLLECTION_NAME
from app.config import settings
from datetime import datetime
import uuid

# 阿里云百炼由于兼容性问题，调用时需要特别注意输入格式
class DashScopeEmbeddings(OpenAIEmbeddings):
    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []
        
        # 对于百炼，使用 check_embedding_ctx 直接获取而不分块，避免内部 token 计算报错
        results = []
        for text in texts:
            # 显式使用基础请求，跳过 OpenAI 的分块逻辑
            response = self.client.create(input=text, model=self.model)
            results.append(response.data[0].embedding)
        return results
        
    def embed_query(self, text: str) -> list[float]:
        response = self.client.create(input=text, model=self.model)
        return response.data[0].embedding

embeddings = DashScopeEmbeddings(
    api_key=settings.embedding_api_key,
    base_url=settings.embedding_base_url,
    model=settings.embedding_model
)

text_splitter = RecursiveCharacterTextSplitter(
    chunk_size=500,
    chunk_overlap=50
)

def process_and_store_document(content: str, metadata: dict, version: str, status: str, effective_from: datetime):
    """
    处理并存储文档到 Milvus 向量数据库中。
    
    Args:
        content (str): 文档文本内容
        metadata (dict): 额外的元数据
        version (str): 文档版本
        status (str): 文档状态
        effective_from (datetime): 生效时间
        
    Returns:
        tuple: (文档ID, 分块数量)
    """
    # 1. Chunk the document
    chunks = text_splitter.split_text(content)
    
    # 2. Vectorize the chunks
    vectors = embeddings.embed_documents(chunks)
    
    # 3. Prepare data for Milvus
    client = get_milvus_client()
    
    # We will generate a unique document ID to tie chunks together
    document_id = str(uuid.uuid4())
    
    data = []
    for i, (chunk, vector) in enumerate(zip(chunks, vectors)):
        chunk_metadata = {
            "document_id": document_id,
            "chunk_index": i,
            "text": chunk,
            "version": version,
            "status": status,
            "effective_from": effective_from.isoformat() if effective_from else None,
            **metadata
        }
        data.append({
            "vector": vector,
            **chunk_metadata
        })
        
    # 4. Insert into Milvus
    if data:
        client.insert(
            collection_name=COLLECTION_NAME,
            data=data
        )
    
    return document_id, len(chunks)

def search_knowledge(query: str, top_k: int = 3, version: str = None):
    """
    在 Milvus 向量数据库中进行相似度检索。
    
    Args:
        query (str): 查询文本
        top_k (int): 返回最相似的 K 条结果，默认为 3
        version (str, optional): 可选的文档版本过滤条件
        
    Returns:
        list: 检索结果列表
    """
    client = get_milvus_client()
    
    # 1. Vectorize query
    query_vector = embeddings.embed_query(query)
    
    # 2. Build filter expression if version is provided
    filter_expr = f'version == "{version}"' if version else ""
    
    # 3. Search in Milvus
    search_res = client.search(
        collection_name=COLLECTION_NAME,
        data=[query_vector],
        limit=top_k,
        filter=filter_expr,
        output_fields=["text", "document_id", "chunk_index", "version", "status", "effective_from"]
    )
    
    # 4. Format results
    results = []
    if search_res and len(search_res) > 0:
        for hits in search_res:
            for hit in hits:
                results.append({
                    "id": hit.get("id"),
                    "distance": hit.get("distance"),
                    "text": hit.get("entity", {}).get("text"),
                    "document_id": hit.get("entity", {}).get("document_id"),
                    "version": hit.get("entity", {}).get("version")
                })
                
    return results
