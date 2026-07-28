import json
import asyncio
import hashlib
from app.agents.cs_agent import cs_agent
from app.db.redis_client import redis_client
from langchain_core.messages import HumanMessage


class ChatService:
    @staticmethod
    def get_cache_key(message: str) -> str:
        message_hash = hashlib.md5(message.encode('utf-8')).hexdigest()
        return f"ai_chat_cache:{message_hash}"

    @staticmethod
    async def generate_chat_stream(message: str, cache_key: str):
        # 用来收集完整回复以便缓存
        full_response = ""
        
        # 异步流式调用 Agent
        # 注意：astream_events v1 已被弃用，建议后续升至 v2，此处先维持功能不变
        async for event in cs_agent.astream_events(
            {"messages": [HumanMessage(content=message)]},
            version="v1"
        ):
            kind = event["event"]
            if kind == "on_chat_model_stream":
                chunk = event["data"]["chunk"]
                if chunk.content:
                    full_response += chunk.content
                    # 按照 SSE 格式返回数据
                    yield f"data: {json.dumps({'content': chunk.content})}\n\n"
        
        # 将完整结果存入 Redis 缓存，TTL 10 分钟 (600 秒)
        if full_response:
            redis_client.setex(cache_key, 600, full_response)
            
        yield "data: [DONE]\n\n"

    @staticmethod
    async def generate_cached_stream(cached_response: str):
        # 如果有缓存，稍微切分一下模拟流式
        chunk_size = 10
        for i in range(0, len(cached_response), chunk_size):
            chunk = cached_response[i:i+chunk_size]
            yield f"data: {json.dumps({'content': chunk})}\n\n"
            await asyncio.sleep(0.01)
        yield "data: [DONE]\n\n"

    @classmethod
    def get_chat_response_generator(cls, message: str):
        """
        核心业务逻辑：检查缓存，决定返回真实大模型流还是缓存流
        """
        cache_key = cls.get_cache_key(message)
        cached_response = redis_client.get(cache_key)
        
        if cached_response:
            return cls.generate_cached_stream(cached_response)
        
        return cls.generate_chat_stream(message, cache_key)