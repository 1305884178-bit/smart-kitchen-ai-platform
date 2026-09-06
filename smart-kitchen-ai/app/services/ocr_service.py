"""
OCR 服务：图片（png/jpg/jpeg）与扫描版 PDF 的文字识别。

- 引擎使用 rapidocr-onnxruntime（纯 CPU、依赖轻）；未安装时给出明确错误，不静默失败；
- PDF 用 PyMuPDF 逐页渲染为图片后 OCR；
- 不引入版面模型（LayoutLM / PP-Structure）。
"""
import io
import logging
import os

logger = logging.getLogger(__name__)

SUPPORTED_IMAGE_EXT = {".png", ".jpg", ".jpeg"}

_engine = None
_engine_checked = False


def _get_engine():
    """惰性初始化 OCR 引擎；不可用时返回 None（由调用方给出明确错误）。"""
    global _engine, _engine_checked
    if _engine_checked:
        return _engine
    _engine_checked = True
    try:
        from rapidocr_onnxruntime import RapidOCR
        _engine = RapidOCR()
        logger.info("[OCR] rapidocr-onnxruntime 引擎初始化完成")
    except Exception as e:
        logger.warning(f"[OCR] rapidocr-onnxruntime 不可用: {e}")
        _engine = None
    return _engine


def ocr_image_bytes(data: bytes) -> str:
    engine = _get_engine()
    if engine is None:
        raise RuntimeError("OCR 功能不可用：服务未安装 rapidocr-onnxruntime")
    import numpy as np
    from PIL import Image
    img = Image.open(io.BytesIO(data)).convert("RGB")
    result, _ = engine(np.array(img))
    if not result:
        return ""
    # rapidocr 返回 [[box, text, score], ...]
    return "\n".join(line[1] for line in result if line and line[1])


def ocr_pdf_bytes(data: bytes) -> str:
    import fitz  # PyMuPDF
    texts = []
    with fitz.open(stream=data, filetype="pdf") as doc:
        for page in doc:
            pix = page.get_pixmap(dpi=200)
            text = ocr_image_bytes(pix.tobytes("png"))
            if text:
                texts.append(text)
    return "\n".join(texts)


def extract_text_ocr(filename: str, data: bytes) -> str:
    """按文件类型 OCR，返回识别文本；不支持的格式抛出明确错误。"""
    ext = os.path.splitext((filename or "").lower())[1]
    if ext in SUPPORTED_IMAGE_EXT:
        return ocr_image_bytes(data)
    if ext == ".pdf":
        return ocr_pdf_bytes(data)
    raise RuntimeError(f"OCR 仅支持图片（png/jpg/jpeg）或 PDF 文件，不支持「{ext or '未知'}」格式")
