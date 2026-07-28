from fastapi import FastAPI
from app.api import knowledge, chat

app = FastAPI(title="Smart Kitchen AI", version="1.0.0")

app.include_router(knowledge.router)
app.include_router(chat.router)

@app.get("/")
def read_root():
    return {"message": "Welcome to Smart Kitchen AI"}
