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
    llm_base_url = os.getenv("LLM_BASE_URL", "https://api.openai.com/v1")
    llm_model = os.getenv("LLM_MODEL", "gpt-4o")

    embedding_api_key = os.getenv("EMBEDDING_API_KEY", "")
    embedding_base_url = os.getenv("EMBEDDING_BASE_URL", "https://api.openai.com/v1")
    embedding_model = os.getenv("EMBEDDING_MODEL", "text-embedding-3-small")

    weather_api_key = os.getenv("WEATHER_API_KEY", "")
    weather_city = os.getenv("WEATHER_CITY", "Shenzhen")

    java_api_url = os.getenv("JAVA_API_URL", "http://localhost:8080")

    app_port = int(os.getenv("APP_PORT", "8000"))

settings = Settings()
