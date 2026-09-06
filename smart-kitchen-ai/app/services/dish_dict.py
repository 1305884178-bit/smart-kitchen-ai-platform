"""
菜品名称词典：从 MySQL pms_dish 加载菜品名，供
- query 改写（识别历史消息中的菜名）
- 混合检索关键词加权（query 与 chunk 的字面命中）
使用。带内存 TTL 缓存；MySQL 不可用时降级为空词典（对应功能自动退化为纯向量检索/不改写），
绝不影响聊天主流程。
"""
import logging
import time

from app.db.mysql_client import get_db_connection

logger = logging.getLogger(__name__)

_CACHE_TTL_SECONDS = 300
_cache_names: list[str] = []
_cache_ts: float = 0.0


def get_dish_names(force_refresh: bool = False) -> list[str]:
    global _cache_names, _cache_ts
    now = time.time()
    if not force_refresh and _cache_names and now - _cache_ts < _CACHE_TTL_SECONDS:
        return _cache_names
    try:
        conn = get_db_connection()
        try:
            with conn.cursor() as cursor:
                cursor.execute("SELECT name FROM pms_dish")
                rows = cursor.fetchall()
        finally:
            conn.close()
        names = [r["name"] for r in rows if r.get("name")]
        if names:
            _cache_names = names
            _cache_ts = now
    except Exception as e:
        logger.warning(f"[DishDict] 加载菜品词典失败，沿用旧缓存/空词典: {e}")
    return _cache_names


def find_dish_in_text(text: str, dish_names: list[str] | None = None) -> str | None:
    """返回文本中最后出现（最可能是当前话题）的菜品名；没有则 None。"""
    if not text:
        return None
    names = dish_names if dish_names is not None else get_dish_names()
    best, best_pos = None, -1
    for name in names:
        pos = text.rfind(name)
        if pos > best_pos:
            best, best_pos = name, pos
    return best
