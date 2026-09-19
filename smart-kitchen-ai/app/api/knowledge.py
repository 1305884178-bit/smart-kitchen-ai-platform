from fastapi import APIRouter, HTTPException, UploadFile, File, Depends
from app.models.schemas import (
    DocumentProcessRequest,
    DocumentProcessResponse,
    KnowledgeSearchRequest,
    KnowledgeSearchResponse,
    DocumentDeleteRequest,
    DocumentDeleteResponse,
    OcrResponse
)
from app.services.rag_service import (
    process_and_store_document,
    search_knowledge,
    delete_chunks_by_document_id,
    get_document_content
)
from app.services.ocr_service import extract_text_ocr
from app.utils.auth import verify_internal_token

# 服务间接口：配置 AI_INTERNAL_TOKEN 后强制校验内部 token（见 utils/auth.py）
router = APIRouter(
    prefix="/ai/knowledge",
    tags=["Knowledge Base"],
    dependencies=[Depends(verify_internal_token)]
)

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
            effective_from=request.effective_from,
            document_id=request.document_id
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

@router.post("/delete", response_model=DocumentDeleteResponse)
async def delete_document(request: DocumentDeleteRequest):
    """
    按 document_id 物理删除 Milvus 中的全部 chunk。
    用于：新版本激活后删除旧版本向量、管理端归档/删除文档、清理任务补偿。
    """
    try:
        deleted = delete_chunks_by_document_id(request.document_id)
        return DocumentDeleteResponse(
            success=True,
            document_id=request.document_id,
            deleted=deleted
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/document/{document_id}")
async def get_document(document_id: str, title: str | None = None):
    """读取历史文档的已存储分块；早期 UUID 数据按精确标题兼容恢复。"""
    try:
        return {"content": get_document_content(document_id, title)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/ocr", response_model=OcrResponse)
async def ocr_file(file: UploadFile = File(...)):
    """
    OCR 识别：图片（png/jpg/jpeg）或扫描版 PDF 转纯文本。
    音频/视频等不支持的格式返回明确错误。
    """
    try:
        data = await file.read()
        text = extract_text_ocr(file.filename or "", data)
        return OcrResponse(success=True, text=text)
    except RuntimeError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"OCR 识别失败：{e}")
