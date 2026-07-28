from fastapi import APIRouter, HTTPException
from app.models.schemas import (
    DocumentProcessRequest,
    DocumentProcessResponse,
    KnowledgeSearchRequest,
    KnowledgeSearchResponse
)
from app.services.rag_service import process_and_store_document, search_knowledge

router = APIRouter(prefix="/ai/knowledge", tags=["Knowledge Base"])

@router.post("/process", response_model=DocumentProcessResponse)
async def process_document(request: DocumentProcessRequest):
    """
    处理文档请求：接收文本内容，进行分块并向量化存储到 Milvus。
    
    Args:
        request (DocumentProcessRequest): 包含文本及版本信息的请求体
        
    Returns:
        DocumentProcessResponse: 包含处理状态及分块数量的响应
    """
    try:
        document_id, chunk_count = process_and_store_document(
            content=request.content,
            metadata=request.metadata,
            version=request.version,
            status=request.status,
            effective_from=request.effective_from
        )
        return DocumentProcessResponse(
            success=True,
            message="Document processed and stored successfully",
            document_id=document_id,
            chunk_count=chunk_count
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/search", response_model=KnowledgeSearchResponse)
async def search_docs(request: KnowledgeSearchRequest):
    """
    检索文档请求：根据查询语句在 Milvus 中检索相关知识片段。
    
    Args:
        request (KnowledgeSearchRequest): 包含查询文本及 top_k 限制的请求体
        
    Returns:
        KnowledgeSearchResponse: 包含相关片段的列表
    """
    try:
        results = search_knowledge(
            query=request.query,
            top_k=request.top_k,
            version=request.version
        )
        return KnowledgeSearchResponse(results=results)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
