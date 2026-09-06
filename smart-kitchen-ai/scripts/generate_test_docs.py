"""
生成 C 端 AI 知识库测试文档（v2.0）：
- 5 份 Word(.docx)
- 5 份 PDF(.pdf)
- 4 份纯文本(.txt)，内容同时打印到控制台，可直接粘贴到管理端「文档内容」框

语料定义在 scripts/knowledge_corpus.py（无重依赖），本脚本只负责文件生成；
eval/run_eval.py 直接复用同一语料作为 golden 知识。
"""
import os

from docx import Document
from docx.shared import Pt
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import SimpleDocTemplate, Paragraph, HRFlowable

from knowledge_corpus import DOC_NOTE, DISHES, SECTION_TITLES, SECTION_ORDER, doc_text

BASE_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "test_docs")


def make_docx(name, d, out_dir):
    doc = Document()
    doc.add_heading(f"菜品知识卡：{name}", level=0)
    meta = doc.add_paragraph("文档版本：v2.0　状态：active　生效日期：2026-08-16")
    meta.runs[0].font.size = Pt(9)
    note = doc.add_paragraph(DOC_NOTE)
    note.runs[0].font.size = Pt(9)

    for key in SECTION_ORDER:
        doc.add_heading(SECTION_TITLES[key], level=1)
        doc.add_paragraph(d[key])

    path = os.path.join(out_dir, f"{name}.docx")
    doc.save(path)
    return path


def make_pdf(name, d, out_dir, font_name):
    path = os.path.join(out_dir, f"{name}.pdf")
    doc = SimpleDocTemplate(
        path, pagesize=A4,
        leftMargin=20 * mm, rightMargin=20 * mm,
        topMargin=18 * mm, bottomMargin=18 * mm,
        title=f"菜品知识卡：{name}",
    )
    title_style = ParagraphStyle("title", fontName=font_name, fontSize=18, leading=24, spaceAfter=4)
    meta_style = ParagraphStyle("meta", fontName=font_name, fontSize=9, leading=13, textColor="#888888")
    h_style = ParagraphStyle("h", fontName=font_name, fontSize=13, leading=18, spaceBefore=10, spaceAfter=3)
    body_style = ParagraphStyle("body", fontName=font_name, fontSize=10.5, leading=16)

    story = [
        Paragraph(f"菜品知识卡：{name}", title_style),
        Paragraph("文档版本：v2.0　状态：active　生效日期：2026-08-16", meta_style),
        Paragraph(DOC_NOTE, meta_style),
        HRFlowable(width="100%", thickness=0.6, color="#cccccc", spaceBefore=6, spaceAfter=2),
    ]
    for key in SECTION_ORDER:
        story.append(Paragraph(SECTION_TITLES[key], h_style))
        story.append(Paragraph(d[key], body_style))

    doc.build(story)
    return path


def main():
    word_dir = os.path.join(BASE_DIR, "word")
    pdf_dir = os.path.join(BASE_DIR, "pdf")
    text_dir = os.path.join(BASE_DIR, "text")
    for p in (word_dir, pdf_dir, text_dir):
        os.makedirs(p, exist_ok=True)

    # 注册中文字体（macOS 系统自带 STHeiti）
    font_path = "/System/Library/Fonts/STHeiti Medium.ttc"
    pdfmetrics.registerFont(TTFont("STHeiti", font_path, subfontIndex=0))

    created = []
    for name, d in DISHES.items():
        if d["format"] == "word":
            created.append(make_docx(name, d, word_dir))
        elif d["format"] == "pdf":
            created.append(make_pdf(name, d, pdf_dir, "STHeiti"))
        else:
            path = os.path.join(text_dir, f"{name}.txt")
            with open(path, "w", encoding="utf-8") as f:
                f.write(doc_text(name, d))
            created.append(path)

    print("已生成以下测试文档：")
    for p in created:
        print(" -", p)


if __name__ == "__main__":
    main()
