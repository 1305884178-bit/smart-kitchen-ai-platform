import redis
from app.config import settings

def get_redis_client():
    """获取 Redis 客户端"""
    return redis.Redis(
        host=settings.redis_host,
        port=settings.redis_port,
        password=settings.redis_pwd,
        db=settings.redis_db,
        decode_responses=True
    )

redis_client = get_redis_client()
