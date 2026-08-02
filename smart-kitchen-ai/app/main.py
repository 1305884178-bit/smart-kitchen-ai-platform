from fastapi import FastAPI
from contextlib import asynccontextmanager
from apscheduler.schedulers.asyncio import AsyncIOScheduler
from app.api import knowledge, chat, predict
from app.services.predict_service import trigger_prediction
import logging

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

scheduler = AsyncIOScheduler()

async def daily_predict_job():
    logger.info("Executing daily predict job at 02:00")
    await trigger_prediction()

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup
    scheduler.add_job(daily_predict_job, 'cron', hour=2, minute=0)
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
