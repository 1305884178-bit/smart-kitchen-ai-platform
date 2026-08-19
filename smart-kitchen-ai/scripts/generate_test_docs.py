"""
生成 C 端 AI 知识库测试文档（v2.0）：
- 5 份 Word(.docx)
- 5 份 PDF(.pdf)
- 4 份纯文本(.txt)，内容同时打印到控制台，可直接粘贴到管理端「文档内容」框

设计原则（v2.0 调整）：
知识库只存数据库没有的“软知识”——口味、口感、适合人群、推荐搭配、常见问法等；
价格、配料、过敏原、库存、分类等已在 B 端菜品管理配置的数据一律不写进知识库，
避免 Agent 用知识库内容回答而应调用 Java 接口工具的问题，使工具路由更清晰：
  口味/人群/推荐类问题 -> search_dish_by_preference（本知识库）
  配料/过敏原/库存/价格类问题 -> get_dish_ingredients / check_dish_inventory（Java 接口）
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

BASE_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "test_docs")

# 文档头说明：明确告知知识边界，帮助 Agent 判断何时改调 Java 工具
DOC_NOTE = "说明：本卡仅记录口味、口感、适合人群等非结构化知识；价格、配料、过敏原、库存、上下架状态以系统实时数据为准，相关问题请查询菜品数据接口。"

DISHES = {
    # ---------- Word 文档（5 份） ----------
    "玉米萝卜排骨汤": {
        "format": "word",
        "taste_tags": "清淡、鲜甜、不辣、热汤、家常、养生",
        "taste": "汤口清亮鲜甜，甜玉米的自然甜味与肉香融合，入口温润不油腻，咸度低，回口有淡淡枣香，是标准的粤式家常例汤风味。",
        "audience": "适合口味清淡的顾客、老人和儿童，以及注重养生的顾客；常见于家庭聚餐、秋冬暖身场景",
        "pairing": "干炒牛河、蒜蓉炒菜心、白米饭",
        "queries": "“有清淡的汤吗”“小孩能喝什么汤”“有没有不油的老火汤”“想喝点养生的汤”",
        "tips": "建议趁热饮用；汤渣（玉米、萝卜）可食用；偏好浓味的顾客可另配豉油碟",
    },
    "干炒牛河": {
        "format": "word",
        "taste_tags": "咸香、锅气、豉油香、微油、热食、主食、不辣",
        "taste": "豉油焦香浓郁、锅气足，河粉干爽油润不糊口，牛肉滑嫩，豆芽脆口，整体以咸香为主、回味微甜，口味偏重。",
        "audience": "适合喜欢重口味锅气炒粉的年轻顾客；一人食、工作餐、宵夜场景点单率高",
        "pairing": "柠檬茶、玉米萝卜排骨汤",
        "queries": "“有什么主食推荐”“一个人吃什么好”“有没有锅气一点的”“想吃点重口味的”",
        "tips": "建议现点现吃，外卖焖久后口感会下降；可免费加辣；偏好清淡的顾客建议搭配例汤",
    },
    "黑叉烧": {
        "format": "word",
        "taste_tags": "蜜汁、甜咸、焦糖香、软嫩、热菜、下饭、招牌",
        "taste": "蜜汁浓郁带焦糖香，甜咸平衡且偏甜口，肥肉部分入口即化、瘦肉软嫩不柴，酱汁拉丝，风味浓郁。",
        "audience": "适合喜欢甜味烧腊的顾客及有下饭需求的顾客；家庭聚餐、宴请常作为招牌菜点单，儿童接受度高",
        "pairing": "白米饭、蒜蓉炒菜心、铁观音",
        "queries": "“有什么招牌菜”“下饭菜推荐”“想吃甜口的肉”“小朋友喜欢吃什么肉”",
        "tips": "下单可备注偏肥或偏瘦；建议趁热食用；不喜甜的顾客可改选酸甜口的五柳炸蛋",
    },
    "蒜蓉炒菜心": {
        "format": "word",
        "taste_tags": "清淡、蒜香、爽脆、素菜、热菜、低油",
        "taste": "蒜香扑鼻、菜心清甜爽脆，咸度低、油感轻，突出蔬菜本味，是标准的清淡绿叶菜。",
        "audience": "适合素食者、清淡饮食顾客、老人和儿童；常作为大鱼大肉后的解腻搭配",
        "pairing": "黑叉烧、椰子乌鸡汤、白米饭",
        "queries": "“有什么青菜”“想吃点清淡的素菜”“有没有不辣的蔬菜”“老人能吃的蔬菜”",
        "tips": "可免蒜蓉做成清炒；出餐快，适合赶时间的顾客",
    },
    "粉丝蒸扇贝": {
        "format": "word",
        "taste_tags": "鲜甜、蒜香、海鲜、热菜、宴请、蒸菜",
        "taste": "扇贝鲜甜多汁，金银蒜蓉香气浓郁，粉丝吸饱海鲜汁后鲜咸入味，整体鲜而不腥、咸鲜适中。",
        "audience": "适合海鲜爱好者；聚餐、宴请场景的热门热菜，下酒下饭皆宜",
        "pairing": "白米饭、天地一号、蒜蓉炒菜心",
        "queries": "“有什么海鲜”“宴请点什么菜”“想吃扇贝”“聚会推荐什么硬菜”",
        "tips": "请趁热食用，放凉后腥味会上升；人数多时可按位加单",
    },
    # ---------- PDF 文档（5 份） ----------
    "椰子乌鸡汤": {
        "format": "pdf",
        "taste_tags": "清甜、滋润、不辣、热汤、滋补、椰香",
        "taste": "椰香浓郁、汤味清甜回甘，比一般例汤更甜润，几乎无咸感压力，口感顺滑，椰肉软糯可食。",
        "audience": "适合注重养颜滋补的女性顾客、经常熬夜的人群；秋冬进补、女士聚餐场景点单率高",
        "pairing": "白米饭、蒜蓉粉丝娃娃菜",
        "queries": "“有没有滋补的汤”“适合女生喝的汤”“熬夜喝什么汤好”“想要甜一点的汤”",
        "tips": "建议趁热饮用；椰肉软糯可直接食用；偏好咸鲜汤的顾客可改选玉米萝卜排骨汤",
    },
    "铁观音": {
        "format": "pdf",
        "taste_tags": "兰花香、醇厚、回甘、热饮、解腻、无糖",
        "taste": "兰花香高扬，入口醇厚甘鲜、回甘持久，无生涩感，浓淡可通过浸泡时间调节，解腻效果出众。",
        "audience": "适合喜欢传统功夫茶的中老年顾客及商务用餐；吃烧味、油炸菜时有解腻需求的顾客",
        "pairing": "黑叉烧、五柳炸蛋、和味爽鱼皮",
        "queries": "“有什么好茶”“吃烧味配什么茶”“有没有解腻的热饮”“老人家喝什么茶”",
        "tips": "可免费续水；偏好浓茶可告知服务员延长浸泡；对茶碱敏感的顾客晚间建议少饮",
    },
    "和味爽鱼皮": {
        "format": "pdf",
        "taste_tags": "咸鲜、微甜、爽脆、凉菜、下酒、开胃",
        "taste": "鱼皮爽脆弹牙，调味咸鲜带微甜，拌花生芝麻后香气复合，整体清爽开胃，无辣度。",
        "audience": "适合喜欢爽脆口感的顾客；常见于佐酒、开胃前菜场景，年轻客群接受度高",
        "pairing": "珠江啤酒、铁观音",
        "queries": "“有什么凉菜”“下酒菜推荐”“有没有爽脆开胃的”“等菜时先来点什么”",
        "tips": "上桌后请尽快食用以保持爽脆；可免香菜；建议作为头盘先上",
    },
    "蒜蓉粉丝娃娃菜": {
        "format": "pdf",
        "taste_tags": "清淡、蒜香、鲜甜、素菜、蒸菜、热菜",
        "taste": "娃娃菜清甜软嫩，蒜香浓郁但不辛辣，粉丝吸饱汤汁后鲜滑，整体清淡鲜美、少油。",
        "audience": "适合素食者、老人儿童及家庭聚餐；想吃蔬菜又怕寡淡的顾客",
        "pairing": "椰子乌鸡汤、白米饭",
        "queries": "“有什么清淡的蔬菜”“小孩吃的蔬菜推荐”“不辣的素菜有什么”“老人能吃的菜”",
        "tips": "可免蒜蓉；偏好重口味的顾客可要求加辣",
    },
    "五柳炸蛋": {
        "format": "pdf",
        "taste_tags": "酸甜、酥香、蛋香、热菜、下饭、开胃",
        "taste": "炸蛋边缘焦脆、内部蓬松吸汁，糖醋芡汁酸甜明亮并带五柳菜的酸香，开胃下饭，口味偏酸甜。",
        "audience": "适合喜欢酸甜口味的顾客及有下饭需求的顾客；儿童接受度高",
        "pairing": "白米饭、珠江啤酒、柠檬茶",
        "queries": "“有什么下饭菜”“酸甜口的菜有什么”“小孩喜欢吃什么”“开胃菜推荐”",
        "tips": "现炸现做，建议趁热食用；酸甜度可按顾客要求调整",
    },
    # ---------- 纯文本（4 份，直接粘贴内容） ----------
    "柠檬茶": {
        "format": "text",
        "taste_tags": "酸甜、冰爽、果香、冷饮、解腻、解渴",
        "taste": "柠檬清香突出，茶味甘醇不涩，酸甜平衡，冰爽刺激，解腻解辣效果明显。",
        "audience": "适合年轻顾客及有解暑、解腻需求的顾客；搭配重口味小炒的饮品首选",
        "pairing": "干炒牛河、五柳炸蛋",
        "queries": "“有什么解腻的饮料”“想喝冰的”“有没有酸一点的饮品”“吃炒粉配什么喝”",
        "tips": "可选少糖/无糖、少冰/走冰；仅限冷饮，不提供热饮版本",
    },
    "珠江啤酒": {
        "format": "text",
        "taste_tags": "清爽、麦香、低苦、冰镇、酒精、佐餐",
        "taste": "麦香清爽、口感顺滑、苦味低，冰镇后杀口感适中，属于易饮型本地啤酒。",
        "audience": "仅供成年顾客佐餐饮用；朋友聚餐、宵夜场景常见",
        "pairing": "和味爽鱼皮、黑叉烧、五柳炸蛋",
        "queries": "“有什么啤酒”“佐餐喝什么酒”“宵夜配什么酒”",
        "tips": "店内提供免费冰镇服务；未成年人禁止饮酒；请提醒顾客酒后不开车",
    },
    "天地一号": {
        "format": "text",
        "taste_tags": "酸甜、苹果香、气泡、解腻、无酒精、冷饮",
        "taste": "苹果醋风味，酸甜开胃、气泡细腻，酸度中等偏明显，冰镇后更爽口，有助消食解腻。",
        "audience": "适合不饮酒的顾客、儿童及开车的顾客；餐后消食解腻需求",
        "pairing": "黑叉烧、粉丝蒸扇贝",
        "queries": "“不喝酒有什么选择”“小孩能喝什么饮料”“有什么开胃饮料”“吃多了想喝点解腻的”",
        "tips": "建议冰镇后饮用；不喜欢明显酸度的顾客可选择可调糖度的柠檬茶",
    },
    "白米饭": {
        "format": "text",
        "taste_tags": "主食、清淡、热食、百搭",
        "taste": "饭香清爽、粒粒分明、软硬适中，口味清淡百搭，不抢菜味。",
        "audience": "适合所有顾客；搭配烧味、小炒的基础主食",
        "pairing": "黑叉烧、五柳炸蛋、各类小炒",
        "queries": "“有米饭吗”“配什么主食好”“下饭菜配什么”",
        "tips": "可为儿童提供小碗分装；食量大的顾客可改选干炒牛河等炒粉面类主食",
    },
}

SECTION_TITLES = {
    "taste_tags": "口味标签",
    "taste": "口味与口感",
    "audience": "适合人群与场景",
    "pairing": "推荐搭配",
    "queries": "常见顾客问法",
    "tips": "食用与定制提示",
}
SECTION_ORDER = ("taste_tags", "taste", "audience", "pairing", "queries", "tips")


def doc_text(name, d):
    """组装完整文档文本（与 Word/PDF 内的内容一致）"""
    lines = [
        f"菜品知识卡：{name}",
        "文档版本：v2.0　状态：active　生效日期：2026-08-16",
        DOC_NOTE,
        "",
    ]
    for key in SECTION_ORDER:
        lines.append(f"【{SECTION_TITLES[key]}】")
        lines.append(d[key])
        lines.append("")
    return "\n".join(lines).rstrip()


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
