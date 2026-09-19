from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_openai import OpenAIEmbeddings
from app.db.milvus_client import get_milvus_client, COLLECTION_NAME
from app.config import settings
from app.services.dish_dict import get_dish_names
from datetime import datetime
import logging
import uuid

logger = logging.getLogger(__name__)


# 阿里云百炼由于兼容性问题，调用时需要特别注意输入格式
class DashScopeEmbeddings(OpenAIEmbeddings):
    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []

        # 百炼兼容模式支持一次请求多条文本；失败时降级为逐条请求
        try:
            response = self.client.create(input=texts, model=self.model)
            data = sorted(response.data, key=lambda d: d.index)
            return [d.embedding for d in data]
        except Exception as e:
            logger.warning(f"[Embedding] 批量 embedding 失败，降级为逐条请求: {e}")
            results = []
            for text in texts:
                # 显式使用基础请求，跳过 OpenAI 的分块逻辑，避免内部 token 计算报错
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

# 轻量字面匹配用的口味/场景关键词（非穷举，命中即在混合排序中加分）
TASTE_KEYWORDS = [
    "辣", "清淡", "甜", "酸甜", "酸", "咸", "鲜", "锅气", "蒜香", "蜜汁", "椰香",
    "汤", "凉菜", "热菜", "素菜", "海鲜", "主食", "饮料", "热饮", "冷饮",
    "下酒", "开胃", "解腻", "滋补", "养生", "招牌", "下饭", "爽脆", "软嫩",
    "小孩", "儿童", "老人", "素", "冰爽", "气泡", "茶", "酒",
]
# 关键词加权上限，避免字面匹配压过向量语义分
KEYWORD_SCORE_CAP = 0.45
DISH_NAME_BONUS = 0.15
KEYWORD_BONUS = 0.03


def _escape(value: str) -> str:
    """Milvus filter 字符串字面量转义，防表达式注入。"""
    return str(value).replace("\\", "\\\\").replace('"', '\\"')


def _normalize_effective_from(effective_from) -> str:
    """生效时间统一为 ISO 字符串；空值存空串（null 无法被 filter 匹配，禁止写入）。"""
    if not effective_from:
        return ""
    if isinstance(effective_from, datetime):
        return effective_from.isoformat()
    return str(effective_from)


def process_and_store_document(content: str, metadata: dict, version: str, status: str,
                               effective_from: datetime, document_id: str = None):
    """
    处理并存储文档到 Milvus 向量数据库中。

    Args:
        content (str): 文档文本内容
        metadata (dict): 额外的元数据（title 等）
        version (str): 文档版本
        status (str): 文档状态
        effective_from (datetime): 生效时间
        document_id (str, optional): 指定文档 ID（Java 侧传 MySQL 行 id，
            保证元数据与向量可互查）；缺省生成 UUID

    Returns:
        tuple: (文档ID, 分块数量)
    """
    # 1. Chunk the document
    chunks = text_splitter.split_text(content)

    # 2. Vectorize the chunks（批量请求，失败自动降级逐条）
    vectors = embeddings.embed_documents(chunks)

    # 3. Prepare data for Milvus
    client = get_milvus_client()

    document_id = document_id or str(uuid.uuid4())
    effective_str = _normalize_effective_from(effective_from)

    data = []
    for i, (chunk, vector) in enumerate(zip(chunks, vectors)):
        data.append({
            **(metadata or {}),
            "vector": vector,
            "document_id": document_id,
            "chunk_index": i,
            "text": chunk,
            "version": version,
            "status": status,
            "effective_from": effective_str,
        })

    # 4. Insert into Milvus
    if data:
        client.insert(
            collection_name=COLLECTION_NAME,
            data=data
        )

    return document_id, len(chunks)


def _keyword_score(query: str, text: str, dish_names: list[str]) -> float:
    """轻量关键词命中加权：菜名命中权重高于口味词，整体封顶。"""
    if not text:
        return 0.0
    score = 0.0
    for name in dish_names:
        if name and name in query and name in text:
            score += DISH_NAME_BONUS
    for kw in TASTE_KEYWORDS:
        if kw in query and kw in text:
            score += KEYWORD_BONUS
    return min(score, KEYWORD_SCORE_CAP)


def search_knowledge(query: str, top_k: int = 3, version: str = None):
    """
    在 Milvus 向量数据库中进行相似度检索。

    默认只检索 C 端可用知识：status == "active" 且（无生效时间或已生效），
    管理端显式传 version 时才追加版本过滤；draft/archived 永不对 C 端可见。

    混合检索：向量召回放大后按「向量距离 + 关键词命中加权」重排截 top_k，
    再按 RAG_SCORE_THRESHOLD 过滤低相关结果；全部低于阈值时返回空列表，
    由客服走拒答话术，不把噪声塞给模型。

    Args:
        query (str): 查询文本
        top_k (int): 返回最相似的 K 条结果，默认为 3
        version (str, optional): 可选的文档版本过滤条件（管理端用）

    Returns:
        list: 检索结果列表（含 distance，便于评测）
    """
    client = get_milvus_client()

    # 1. Vectorize query
    query_vector = embeddings.embed_query(query)

    # 2. 默认过滤：active 且已生效；仅显式传 version 时追加版本条件
    now_iso = datetime.now().isoformat(timespec="seconds")
    filters = [
        'status == "active"',
        f'(effective_from == "" or effective_from <= "{now_iso}")',
    ]
    if version:
        filters.append(f'version == "{_escape(version)}"')
    filter_expr = " and ".join(filters)

    # 3. Search in Milvus（向量召回放大，留给关键词重排空间）
    recall_limit = max(top_k * 3, 8)
    search_res = client.search(
        collection_name=COLLECTION_NAME,
        data=[query_vector],
        limit=recall_limit,
        filter=filter_expr,
        output_fields=["text", "document_id", "chunk_index", "version", "status",
                       "effective_from", "title"]
    )

    # 4. Format + 混合重排
    hits = []
    if search_res and len(search_res) > 0:
        for hit in search_res[0]:
            entity = hit.get("entity", {})
            hits.append({
                "id": hit.get("id"),
                "distance": hit.get("distance"),
                "text": entity.get("text"),
                "document_id": entity.get("document_id"),
                "chunk_index": entity.get("chunk_index"),
                "title": entity.get("title"),
                "version": entity.get("version"),
            })

    dish_names = get_dish_names()
    for h in hits:
        h["score"] = (h["distance"] or 0) + _keyword_score(query, h.get("text") or "", dish_names)
    hits.sort(key=lambda h: h["score"], reverse=True)
    hits = hits[:top_k]

    # 5. RAG 相似度阈值过滤（与语义缓存阈值独立）；全低于阈值则返回空
    results = [h for h in hits if (h["distance"] or 0) >= settings.rag_score_threshold]
    return results


def delete_chunks_by_document_id(document_id: str) -> int:
    """按 document_id 物理删除 Milvus 中的全部 chunk，返回删除条数。"""
    client = get_milvus_client()
    res = client.delete(
        collection_name=COLLECTION_NAME,
        filter=f'document_id == "{_escape(document_id)}"'
    )
    if isinstance(res, list):
        return len(res)
    if isinstance(res, dict):
        return res.get("delete_count", 0)
    return 0


def get_document_content(document_id: str) -> str:
    """按 chunk_index 恢复一个文档的原文，供内容字段上线前的历史数据迁移使用。"""
    client = get_milvus_client()
    rows = client.query(
        collection_name=COLLECTION_NAME,
        filter=f'document_id == "{_escape(document_id)}"',
        output_fields=["text", "chunk_index"],
        limit=16384
    )
    chunks = sorted(rows or [], key=lambda row: row.get("chunk_index", 0))
    if not chunks:
        return ""

    # 分块之间有 50 字符重叠；仅在相邻边界确实重合时去重，避免错误吞掉正文。
    content = ""
    for row in chunks:
        chunk = row.get("text") or ""
        if not content:
            content = chunk
            continue
        max_overlap = min(100, len(content), len(chunk))
        overlap = 0
        for size in range(max_overlap, 0, -1):
            if content.endswith(chunk[:size]):
                overlap = size
                break
        content += chunk[overlap:]
    return content


def list_all_document_ids() -> set[str]:
    """列出 kitchen_knowledge 中全部 document_id（供孤儿向量清理比对 MySQL 元数据）。"""
    client = get_milvus_client()
    rows = client.query(
        collection_name=COLLECTION_NAME,
        filter='document_id != ""',
        output_fields=["document_id"],
        limit=16384
    )
    return {r["document_id"] for r in rows if r.get("document_id")}
