"""
检索上下文：当前请求用于知识库检索的改写问句。

多轮场景下 ChatService 会把「这个辣不辣」改写为「水煮鱼辣不辣」并写入这里，
search_dish_by_preference 优先使用它调用 search_knowledge，保证检索词含具体菜名；
单轮或无需改写时该值等于用户原句。改写结果只用于检索与语义缓存，不替换用户原话展示。
"""
import contextvars

current_retrieval_query = contextvars.ContextVar("current_retrieval_query", default=None)
