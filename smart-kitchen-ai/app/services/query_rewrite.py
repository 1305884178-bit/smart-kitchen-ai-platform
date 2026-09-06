"""
多轮对话 query 改写：把含指代的短句（「这个辣不辣」）补成带菜名/口味的完整问句。

- 改写结果只用于检索（search_knowledge）与语义缓存 key，不替换用户原话展示；
- 优先规则改写：指代词 + 历史消息（优先上一轮助手内容）里的菜名；
- 规则不够时再单独一次短 prompt LLM 改写；
- 任何失败都回退为原句，不影响主流程。
"""
import logging

from app.config import settings
from app.services.dish_dict import get_dish_names, find_dish_in_text

logger = logging.getLogger(__name__)

# 指代词（长的在前，优先匹配替换）
DEMONSTRATIVES = [
    "这道菜", "这个菜", "那道菜", "那个菜", "这道", "那道", "这个", "那个", "它",
]
# 无菜名且长度不超过该值时视为短追问，尝试补菜名（如「辣不辣」「还有吗」）
SHORT_FOLLOWUP_MAX_LEN = 12
# 纯寒暄/致谢/告别不需要改写
SMALL_TALK = ("你好", "您好", "hi", "hello", "谢谢", "感谢", "再见", "拜拜", "在吗")

REWRITE_PROMPT = """你是查询改写助手。根据对话历史，把顾客最新问题改写成一个包含完整指代信息（菜品名、口味等）的独立问句。
要求：只输出改写后的问句本身，不要解释，不要引号；若原句已完整则原样输出。

对话历史：
{history}

顾客最新问题：{message}
改写后的问句："""

_llm = None


def _get_llm():
    """改写专用 LLM 客户端：短输出、低温、非流式，惰性初始化。"""
    global _llm
    if _llm is None:
        from langchain_openai import ChatOpenAI
        _llm = ChatOpenAI(
            api_key=settings.llm_api_key,
            base_url=settings.llm_base_url,
            model=settings.llm_model,
            temperature=0,
            max_tokens=64,
            streaming=False,
        )
    return _llm


def _history_text(history: list[dict], limit: int = 4) -> str:
    lines = []
    for msg in history[-limit:]:
        role = "顾客" if msg.get("role") == "user" else "客服"
        lines.append(f"{role}：{msg.get('content', '')}")
    return "\n".join(lines)


def _find_topic_dish(history: list[dict], dish_names: list[str]) -> str | None:
    """优先取上一轮助手内容里的菜名，再回看用户消息。"""
    assistants = [m for m in history if m.get("role") == "assistant"]
    users = [m for m in history if m.get("role") == "user"]
    for msg in reversed(assistants):
        name = find_dish_in_text(msg.get("content", ""), dish_names)
        if name:
            return name
    for msg in reversed(users):
        name = find_dish_in_text(msg.get("content", ""), dish_names)
        if name:
            return name
    return None


def _is_small_talk(message: str) -> bool:
    normalized = message.strip().lower().strip("！!~～。.")
    return any(normalized == w or normalized.startswith(w + " ") for w in SMALL_TALK)


def _rule_rewrite(message: str, history: list[dict], dish_names: list[str]) -> str | None:
    """规则改写。返回 None 表示规则不适用或找不到菜名，由调用方决定是否走 LLM。"""
    demonstrative = next((d for d in DEMONSTRATIVES if d in message), None)
    is_short_followup = len(message) <= SHORT_FOLLOWUP_MAX_LEN
    if not demonstrative and not is_short_followup:
        return message  # 完整问句，无需改写
    dish = _find_topic_dish(history, dish_names) if history else None
    if not dish:
        return None
    if demonstrative:
        return message.replace(demonstrative, dish, 1)
    return f"{dish}{message}"


def _llm_rewrite(message: str, history: list[dict]) -> str | None:
    try:
        prompt = REWRITE_PROMPT.format(history=_history_text(history) or "（无）", message=message)
        resp = _get_llm().invoke(prompt)
        rewritten = (resp.content or "").strip().strip('"').strip()
        #  sanity check：非空且长度合理才接受，否则回退原句
        if rewritten and len(rewritten) <= 100:
            return rewritten
    except Exception as e:
        logger.warning(f"[QueryRewrite] LLM 改写失败，回退原句: {e}")
    return None


def rewrite_query(message: str, history: list[dict] | None = None, allow_llm: bool = True) -> str:
    """
    返回用于检索与语义缓存的改写问句。无历史或无需改写时等于原句。

    Args:
        message: 用户原始消息
        history: 当前条之前的对话历史 [{"role", "content"}]
        allow_llm: 规则不够时是否允许 LLM 改写（测试可关闭）
    """
    message = (message or "").strip()
    history = history or []
    if not message or not history or _is_small_talk(message):
        return message

    dish_names = get_dish_names()
    if find_dish_in_text(message, dish_names):
        return message  # 原句已含菜名，无需改写

    rule_result = _rule_rewrite(message, history, dish_names)
    if rule_result:
        if rule_result != message:
            logger.info(f"[QueryRewrite] 规则改写: 「{message}」->「{rule_result}」")
        return rule_result

    if allow_llm:
        llm_result = _llm_rewrite(message, history)
        if llm_result:
            logger.info(f"[QueryRewrite] LLM 改写: 「{message}」->「{llm_result}」")
            return llm_result
    return message
