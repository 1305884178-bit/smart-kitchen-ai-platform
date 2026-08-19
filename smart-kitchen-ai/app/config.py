import os
from dotenv import load_dotenv

load_dotenv()

class Settings:
    mysql_host = os.getenv("DB_HOST", "localhost")
    mysql_port = int(os.getenv("DB_PORT", "3306"))
    mysql_user = os.getenv("DB_USER", "root")
    mysql_pwd = os.getenv("DB_PWD", "")
    mysql_db = os.getenv("DB_NAME", "smart_kitchen")

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

    weather_api_key = os.getenv("WEATHER_API_KEY", "")
    weather_city = os.getenv("WEATHER_CITY", "Shenzhen")

    java_api_url = os.getenv("JAVA_API_URL", "http://localhost:8080")

    app_port = int(os.getenv("APP_PORT", "8000"))

settings = Settings()
