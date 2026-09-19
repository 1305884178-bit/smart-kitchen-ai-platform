from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_openai import OpenAIEmbeddings
from app.db.milvus_client import get_milvus_client, COLLECTION_NAME
from app.config import settings
from app.services.reranker_service import reranker_service
from datetime import datetime
from collections import Counter
from math import log
import logging
import re
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

BM25_K1 = 1.5
BM25_B = 0.75
LEXICAL_SCAN_LIMIT = 16384  # 当前知识库规模下全量读取已发布分块，避免维护第二份易失效索引


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


def _build_filter(version: str | None = None) -> str:
    """Dense 与 BM25 共享同一可见性过滤，禁止草稿/归档知识进入任一召回路径。"""
    now_iso = datetime.now().isoformat(timespec="seconds")
    filters = [
        'status == "active"',
        f'(effective_from == "" or effective_from <= "{now_iso}")',
    ]
    if version:
        filters.append(f'version == "{_escape(version)}"')
    return " and ".join(filters)


def _to_hit(item: dict, *, distance: float | None = None) -> dict:
    """把 Milvus search/query 返回统一为检索候选结构。"""
    entity = item.get("entity", item) or {}
    return {
        "id": item.get("id", entity.get("id")),
        "distance": distance if distance is not None else item.get("distance"),
        "text": entity.get("text"),
        "document_id": entity.get("document_id"),
        "chunk_index": entity.get("chunk_index"),
        "title": entity.get("title"),
        "version": entity.get("version"),
    }


def _chunk_key(hit: dict) -> str:
    """RRF 去重键：优先 Milvus 主键，兼容旧数据中没有 id 的场景。"""
    if hit.get("id") is not None:
        return f"id:{hit['id']}"
    return f"chunk:{hit.get('document_id', '')}:{hit.get('chunk_index', '')}"


def _tokenize_for_bm25(text: str) -> list[str]:
    """中文使用字符二元词，英文/数字保留词元，兼顾菜名精确匹配和口语检索。"""
    tokens: list[str] = []
    for part in re.findall(r"[\u4e00-\u9fff]+|[a-zA-Z0-9]+", (text or "").lower()):
        if re.fullmatch(r"[\u4e00-\u9fff]+", part):
            if len(part) == 1:
                tokens.append(part)
            else:
                tokens.extend(part[i:i + 2] for i in range(len(part) - 1))
        else:
            tokens.append(part)
    return tokens


def _bm25_rank(query: str, rows: list[dict], limit: int) -> list[dict]:
    """标准 Okapi BM25，返回独立于 Dense 的稀疏召回候选。"""
    query_terms = _tokenize_for_bm25(query)
    if not query_terms or not rows:
        return []

    documents = [_tokenize_for_bm25(row.get("text") or "") for row in rows]
    doc_freq: Counter = Counter()
    for terms in documents:
        doc_freq.update(set(terms))
    avg_length = sum(len(terms) for terms in documents) / len(documents) or 1.0

    scored: list[tuple[float, int]] = []
    for index, terms in enumerate(documents):
        term_freq = Counter(terms)
        score = 0.0
        for term in set(query_terms):
            frequency = term_freq.get(term, 0)
            if not frequency:
                continue
            idf = log(1 + (len(documents) - doc_freq[term] + 0.5) / (doc_freq[term] + 0.5))
            denominator = frequency + BM25_K1 * (1 - BM25_B + BM25_B * len(terms) / avg_length)
            score += idf * frequency * (BM25_K1 + 1) / denominator
        if score > 0:
            scored.append((score, index))

    ranked = []
    for score, index in sorted(scored, key=lambda item: item[0], reverse=True)[:limit]:
        hit = _to_hit(rows[index])
        hit["bm25_score"] = score
        ranked.append(hit)
    return ranked


def _dense_recall(client, query_vector: list[float], filter_expr: str, limit: int) -> list[dict]:
    search_res = client.search(
        collection_name=COLLECTION_NAME,
        data=[query_vector],
        limit=limit,
        filter=filter_expr,
        output_fields=["text", "document_id", "chunk_index", "version", "status",
                       "effective_from", "title"],
    )
    if not search_res:
        return []
    return [_to_hit(hit, distance=hit.get("distance")) for hit in search_res[0]]


def _bm25_recall(client, query: str, filter_expr: str, limit: int) -> list[dict]:
    """读取与 Dense 相同过滤范围的分块，临时计算 BM25，避免索引生命周期不一致。"""
    try:
        rows = client.query(
            collection_name=COLLECTION_NAME,
            filter=filter_expr,
            output_fields=["id", "text", "document_id", "chunk_index", "version", "title"],
            limit=LEXICAL_SCAN_LIMIT,
        )
        return _bm25_rank(query, rows or [], limit)
    except Exception as exc:
        logger.warning("[RAG] BM25 召回失败，仅使用 Dense 结果: %s", exc)
        return []


def _rrf_fuse(dense_hits: list[dict], bm25_hits: list[dict], rrf_k: int) -> list[dict]:
    """Reciprocal Rank Fusion：融合不同分数尺度的 Dense 与 BM25 排名。"""
    fused: dict[str, dict] = {}
    for source, hits in (("dense", dense_hits), ("bm25", bm25_hits)):
        for rank, hit in enumerate(hits, start=1):
            key = _chunk_key(hit)
            candidate = fused.setdefault(key, dict(hit, rrf_score=0.0, retrieval_sources=[]))
            candidate["rrf_score"] += 1 / (rrf_k + rank)
            candidate["retrieval_sources"].append(source)
            if source == "dense":
                candidate["distance"] = hit.get("distance")
                candidate["dense_rank"] = rank
            else:
                candidate["bm25_score"] = hit.get("bm25_score")
                candidate["bm25_rank"] = rank
    return sorted(fused.values(), key=lambda item: item["rrf_score"], reverse=True)


def search_knowledge(query: str, top_k: int = 3, version: str = None):
    """
    在 Milvus 向量数据库中进行混合检索。

    默认只检索 C 端可用知识：status == "active" 且（无生效时间或已生效），
    管理端显式传 version 时才追加版本过滤；draft/archived 永不对 C 端可见。

    Dense 与 BM25 各自粗召回，再以 RRF 融合；配置云端 Reranker 后才对融合候选重排。
    未配置、超时或响应异常时严格降级为 RRF Top-K。RAG_SCORE_THRESHOLD 仅用于
    Dense-only 候选的噪声过滤；BM25 命中的实体候选可进入后续融合与重排。

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
    filter_expr = _build_filter(version)

    # 2. Dense / BM25 独立粗召回。任一分支故障不影响另一分支。
    dense_limit = max(top_k, settings.rag_dense_recall_limit)
    bm25_limit = max(top_k, settings.rag_bm25_recall_limit)
    dense_hits = _dense_recall(client, query_vector, filter_expr, dense_limit)
    bm25_hits = _bm25_recall(client, query, filter_expr, bm25_limit)

    # 3. RRF 融合后先去掉无词法证据且低于 Dense 相关度阈值的噪声。
    fused = _rrf_fuse(dense_hits, bm25_hits, max(settings.rag_rrf_k, 1))
    fused = [
        hit for hit in fused
        if hit.get("bm25_score", 0) > 0
        or (hit.get("distance") or 0) >= settings.rag_score_threshold
    ]
    candidate_limit = max(top_k, settings.rag_fusion_candidate_limit)
    fused = fused[:candidate_limit]
    if not fused:
        return []

    # 4. 可选云端 Reranker；未配置/失败时返回 None，严格降级到 RRF 结果。
    reranked = reranker_service.rerank(query, fused, top_k)
    results = reranked if reranked is not None else fused[:top_k]
    strategy = "reranker" if reranked is not None else "rrf_fallback"
    for hit in results:
        hit["retrieval_strategy"] = strategy
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


def get_document_content(document_id: str, title: str | None = None) -> str:
    """按 chunk_index 恢复一个文档的原文，兼容早期 UUID document_id 的历史数据。"""
    client = get_milvus_client()
    rows = client.query(
        collection_name=COLLECTION_NAME,
        filter=f'document_id == "{_escape(document_id)}"',
        output_fields=["id", "text", "chunk_index", "document_id"],
        limit=16384
    )

    # 早期上传使用了 Python 生成的 UUID，而 MySQL 元数据使用自增 ID。
    # 两者无法通过 ID 对应时，按精确标题选择最近写入的一组分块恢复。
    if not rows and title:
        title_rows = client.query(
            collection_name=COLLECTION_NAME,
            filter=f'title == "{_escape(title)}"',
            output_fields=["id", "text", "chunk_index", "document_id"],
            limit=16384
        )
        by_document = {}
        for row in title_rows or []:
            by_document.setdefault(row.get("document_id"), []).append(row)
        if by_document:
            rows = max(
                by_document.values(),
                key=lambda group: max(item.get("id", 0) for item in group)
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
