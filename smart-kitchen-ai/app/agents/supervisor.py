import json
import os
import logging
from typing import TypedDict, Optional, Literal
from langgraph.graph import StateGraph, END, START
from langchain_openai import ChatOpenAI
from app.config import settings
from app.agents.predict_agent import predict_subgraph

logger = logging.getLogger(__name__)

PROMPTS_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "prompts")


def _load_prompt(filename: str) -> str:
    """加载 prompts 目录下的提示词文件"""
    with open(os.path.join(PROMPTS_DIR, filename), "r", encoding="utf-8") as f:
        return f.read()


class SupervisorState(TypedDict, total=False):
    """Supervisor 的状态（子图的超集）"""
    task_type: str
    route: str
    predict_date: Optional[str]
    dish_id: Optional[int]
    sales_30d: Optional[list]
    weather: Optional[dict]
    holiday: Optional[dict]
    recent_reviews: Optional[dict]
    time_series_result: Optional[int]
    ai_suggest_quantity: Optional[int]
    reasoning: Optional[str]
    confidence: Optional[float]
    recent_avg_score: Optional[float]
    final_quantity: Optional[int]
    error: Optional[str]


async def supervisor_node(state: SupervisorState) -> dict:
    """
    Supervisor 路由节点：根据 task_type 决定调用哪个子图。
    """
    task_type = state.get("task_type", "")
    logger.info(f"Supervisor routing task_type={task_type}")

    # 空或 null → none
    if not task_type:
        return {"route": "none"}

    # 已知类型 → 对应子图
    if task_type == "predict":
        return {"route": "predict_subgraph"}

    # 未知类型 → other 兜底
    logger.warning(f"Unknown task_type: {task_type}, routing to other")
    return {"route": "other"}


def other_node(state: SupervisorState) -> dict:
    """
    未知类型兜底节点：task_type 有值但不在已知列表中时，返回不支持提示。
    """
    task_type = state.get("task_type", "")
    return {"route": "none", "reason": f"不支持的任务类型: {task_type}"}


# 构建 Supervisor 图
builder = StateGraph(SupervisorState)

builder.add_node("supervisor_node", supervisor_node)
builder.add_node("predict_subgraph", predict_subgraph)
builder.add_node("other_node", other_node)

builder.add_edge(START, "supervisor_node")

# 条件路由：supervisor_node 决定走哪个子图
builder.add_conditional_edges(
    "supervisor_node",
    lambda state: state.get("route", "predict_subgraph"),
    {
        "predict_subgraph": "predict_subgraph",
        "other": "other_node",
        "none": END,
    }
)

builder.add_edge("predict_subgraph", END)
builder.add_edge("other_node", END)

supervisor = builder.compile()
