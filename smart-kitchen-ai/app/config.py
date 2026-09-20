import os
from dotenv import load_dotenv

load_dotenv()

class Settings:
    mysql_host = os.getenv("DB_HOST", "localhost")
    mysql_port = int(os.getenv("DB_PORT", "3306"))
    mysql_user = os.getenv("DB_USER", "root")
    mysql_pwd = os.getenv("DB_PWD", "")
    mysql_db = os.getenv("DB_NAME", "smart_kitchen")
    # Python 侧 MySQL 连接池上限（个位数，避免与 Java Hikari 抢连接）；生产建议配只读账号
    db_pool_size = int(os.getenv("DB_POOL_SIZE", "5"))

    redis_host = os.getenv("REDIS_HOST", "localhost")
    redis_port = int(os.getenv("REDIS_PORT", "6379"))
    redis_pwd = os.getenv("REDIS_PWD", "")
    redis_db = int(os.getenv("REDIS_DB", "0"))

    milvus_host = os.getenv("MILVUS_HOST", "localhost")
    milvus_port = os.getenv("MILVUS_PORT", "19530")

    llm_api_key = os.getenv("LLM_API_KEY", "")
    llm_base_url = os.getenv("LLM_BASE_URL", "https://api.deepseek.com")
    llm_model = os.getenv("LLM_MODEL", "deepseek-v4-pro")

    embedding_api_key = os.getenv("EMBEDDING_API_KEY", "")
    embedding_base_url = os.getenv("EMBEDDING_BASE_URL", "https://api.openai.com/v1")
    embedding_model = os.getenv("EMBEDDING_MODEL", "text-embedding-3-small")

    # AI 客服语义缓存：问题 embedding 与历史问题向量算余弦相似度，>= 阈值即命中
    semantic_cache_threshold = float(os.getenv("SEMANTIC_CACHE_THRESHOLD", "0.92"))
    semantic_cache_collection = os.getenv("SEMANTIC_CACHE_COLLECTION", "ai_chat_semantic_cache")
    # 缓存条目保鲜期（天），超期不再参与命中；知识库更新则通过 kb_version 指纹即时失效
    semantic_cache_ttl_days = int(os.getenv("SEMANTIC_CACHE_TTL_DAYS", "7"))
    # 知识库版本指纹在 Redis 中的缓存秒数，避免每次提问都查 MySQL
    kb_version_cache_ttl = int(os.getenv("KB_VERSION_CACHE_TTL", "60"))

    # RAG 知识库检索相似度阈值（COSINE distance 下限，低于则丢弃）。
    # 注意：与语义缓存阈值 0.92 相互独立，禁止共用。
    # 调参依据（eval/run_eval.py，2026-09-06，text-embedding-v4 + 14 份菜品知识卡）：
    #   0.60 → Recall@3≈35%；0.55 → Recall@3≈71%；低相关 query 空结果率均为 100%
    #   （无关 query 距离上限≈0.32，相关命中下限≈0.44，0.55 落在间隔内）。
    #   embedding 接口存在 ±0.02 抖动，边界 case 在 0.55~0.60 间敏感，微调请重跑评测。
    rag_score_threshold = float(os.getenv("RAG_SCORE_THRESHOLD", "0.55"))
    # RAG 粗召回与融合：Dense / BM25 各自独立召回后使用 RRF 融合。
    rag_dense_recall_limit = int(os.getenv("RAG_DENSE_RECALL_LIMIT", "12"))
    rag_bm25_recall_limit = int(os.getenv("RAG_BM25_RECALL_LIMIT", "12"))
    rag_fusion_candidate_limit = int(os.getenv("RAG_FUSION_CANDIDATE_LIMIT", "10"))
    rag_rrf_k = int(os.getenv("RAG_RRF_K", "60"))
    # 可选云端 Reranker。未同时配置开关、URL 和 Key 时严格走 RRF 降级，不发起网络调用。
    reranker_enabled = os.getenv("RERANKER_ENABLED", "false").strip().lower() in {"1", "true", "yes", "on"}
    reranker_api_url = os.getenv("RERANKER_API_URL", "").strip()
    reranker_api_key = os.getenv("RERANKER_API_KEY", "").strip()
    reranker_model = os.getenv("RERANKER_MODEL", "").strip()
    reranker_timeout_seconds = float(os.getenv("RERANKER_TIMEOUT_SECONDS", "5"))

    # 归档知识文档的 Milvus 向量保留天数，超过后由定时任务物理删除（active 文档永不过期）
    kb_archived_retention_days = int(os.getenv("KB_ARCHIVED_RETENTION_DAYS", "7"))

    # 多轮对话：送入 ReAct Agent 的最近消息条数（含当前条），禁止全量历史
    chat_history_max_messages = int(os.getenv("CHAT_HISTORY_MAX_MESSAGES", "8"))

    # /ai/chat 鉴权：与 Java 端一致的 HS256 JWT 密钥，必须显式注入。
    jwt_secret = os.getenv("JWT_SECRET", "").strip()
    # Java 服务间调用的内部 token（配置后 /ai/knowledge、/ai/predict 强制校验）
    ai_internal_token = os.getenv("AI_INTERNAL_TOKEN", "")
    # 客服接口限流：每用户/每 IP 每分钟最大请求数
    chat_rate_limit_per_minute = int(os.getenv("CHAT_RATE_LIMIT_PER_MINUTE", "30"))
    # 客服取消标记与所属用户的保存时间；正常回答通常会在此时间内结束。
    chat_cancel_ttl_seconds = int(os.getenv("CHAT_CANCEL_TTL_SECONDS", "300"))

    weather_api_key = os.getenv("WEATHER_API_KEY", "")
    weather_city = os.getenv("WEATHER_CITY", "Shenzhen")

    java_api_url = os.getenv("JAVA_API_URL", "http://localhost:8080")

    app_port = int(os.getenv("APP_PORT", "8000"))

settings = Settings()


def validate_security_settings() -> None:
    """Require an explicitly injected signing key in production."""
    environment = os.getenv("APP_ENV", os.getenv("ENVIRONMENT", "")).strip().lower()
    if environment not in {"prod", "production"}:
        return
    if len(settings.jwt_secret.encode("utf-8")) < 32:
        raise RuntimeError(
            "Production requires JWT_SECRET to be explicitly configured with at least 32 bytes."
        )
