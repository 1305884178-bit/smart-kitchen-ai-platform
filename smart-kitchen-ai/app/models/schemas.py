from pydantic import BaseModel
from typing import Optional, List, Dict, Any
from datetime import datetime

class DocumentProcessRequest(BaseModel):
    content: str
    metadata: Optional[Dict[str, Any]] = {}
    version: Optional[str] = "1.0"
    status: Optional[str] = "active"
    effective_from: Optional[datetime] = None
    # Java 侧传入 MySQL 元数据行 id，保证向量与元数据可互查；缺省由服务端生成 UUID
    document_id: Optional[str] = None

class DocumentProcessResponse(BaseModel):
    success: bool
    message: str
    document_id: str
    chunk_count: int

class KnowledgeSearchRequest(BaseModel):
    query: str
    top_k: Optional[int] = 3
    version: Optional[str] = None

class KnowledgeSearchResponse(BaseModel):
    results: List[Dict[str, Any]]

class DocumentDeleteRequest(BaseModel):
    document_id: str

class DocumentDeleteResponse(BaseModel):
    success: bool
    document_id: str
    deleted: int

class OcrResponse(BaseModel):
    success: bool
    text: str
