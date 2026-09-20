import asyncio

from fastapi import FastAPI
from contextlib import asynccontextmanager
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from app.api import knowledge, chat, predict
from app.services.predict_service import trigger_prediction
from app.services.kb_cleanup_service import run_cleanup_job
from app.config import validate_security_settings
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

scheduler = AsyncIOScheduler()

async def daily_predict_job():
    logger.info("Executing daily predict job at 02:00")
    await trigger_prediction()

async def kb_cleanup_job():
    """归档超期向量 + 孤儿向量清理；阻塞 IO 放到线程池，失败只打日志。"""
    logger.info("Executing KB cleanup job")
    loop = asyncio.get_running_loop()
    await loop.run_in_executor(None, run_cleanup_job)

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    validate_security_settings()
    scheduler.add_job(daily_predict_job, 'cron', hour=2, minute=0)
    # 知识库清理：每天 03:30；active 文档不设 TTL、永不过期
    scheduler.add_job(kb_cleanup_job, 'cron', hour=3, minute=30)
    scheduler.start()
    yield
    # Shutdown
    scheduler.shutdown()

app = FastAPI(title="Smart Kitchen AI", version="1.0.0", lifespan=lifespan)

app.include_router(knowledge.router)
app.include_router(chat.router)
app.include_router(predict.router)

@app.get("/")
def read_root():
    return {"message": "Welcome to Smart Kitchen AI"}
