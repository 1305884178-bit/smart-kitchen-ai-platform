import json
import os
import logging
import re
from typing import TypedDict, Optional, List, Dict, Any
from langgraph.graph import StateGraph, START, END
from langchain_openai import ChatOpenAI
from app.config import settings
from app.tools.predict_tools import get_sales_30d, get_recent_reviews
from app.utils.mcp_client import call_mcp_tool
from app.db.mysql_client import get_db_connection

logger = logging.getLogger(__name__)

PROMPTS_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "prompts")


def _load_prompt(filename: str) -> str:
    """加载 prompts 目录下的提示词文件"""
    with open(os.path.join(PROMPTS_DIR, filename), "r", encoding="utf-8") as f:
        return f.read()


class PredictState(TypedDict):
    predict_date: str
    dish_id: int
    sales_30d: Optional[List[Dict]]
    weather: Optional[Dict]
    holiday: Optional[Dict]
    recent_reviews: Optional[Dict]
    time_series_result: Optional[int]
    ts_avg_30d: Optional[int]
    ts_avg_7d: Optional[int]
    ts_median_30d: Optional[int]
    ts_fallback_level: Optional[int]
    fallback_category_avg: Optional[int]
    fallback_initial_stock: Optional[int]
    fallback_daily_stock: Optional[int]
    base_quantity: Optional[int]
    ai_suggest_quantity: Optional[int]
    reasoning: Optional[str]
    confidence: Optional[float]
    recent_avg_score: Optional[float]
    final_quantity: Optional[int]
    error: Optional[str]


async def get_sales_30d_node(state: PredictState) -> PredictState:
    """获取过去30天销量数据"""
    try:
        sales = get_sales_30d(state['dish_id'])
        return {"sales_30d": sales}
    except Exception as e:
        logger.error(f"Error in get_sales_30d: {e}")
        return {"sales_30d": []}


async def get_tomorrow_weather_node(state: PredictState) -> PredictState:
    """获取明天天气（MCP调用）"""
    try:
        weather = await call_mcp_tool("weather_server", "get_tomorrow_weather", {"date": state['predict_date']})
        return {"weather": weather}
    except Exception as e:
        logger.error(f"Error getting weather: {e}")
        return {"weather": {}}


async def get_holiday_info_node(state: PredictState) -> PredictState:
    """获取节假日信息（MCP调用）"""
    try:
        holiday = await call_mcp_tool("holiday_server", "get_holiday_info", {"date": state['predict_date']})
        return {"holiday": holiday}
    except Exception as e:
        logger.error(f"Error getting holiday: {e}")
        return {"holiday": {}}


async def get_recent_reviews_node(state: PredictState) -> PredictState:
    """获取近期评价数据"""
    try:
        reviews = get_recent_reviews(state['dish_id'])
        return {
            "recent_reviews": reviews,
            "recent_avg_score": reviews.get("avg_score", 5.0)
        }
    except Exception as e:
        logger.error(f"Error in get_recent_reviews: {e}")
        return {"recent_reviews": {}, "recent_avg_score": 5.0}


def _cold_start_fallback(dish_id: int) -> dict:
    """冷启动逐级降级：同类菜品均值 → 新品初始库存 → daily_stock → 绝对兜底"""
    conn = get_db_connection()
    try:
        with conn.cursor() as cursor:
            # 级别2：同类菜品近30天日均销量
            cursor.execute("""
                SELECT AVG(t.daily_avg) as category_avg
                FROM (
                    SELECT od.dish_id, AVG(od.quantity) as daily_avg
                    FROM oms_order_detail od
                    JOIN pms_dish d ON od.dish_id = d.id
                    WHERE d.category_id = (
                        SELECT category_id FROM pms_dish WHERE id = %s
                    )
                      AND d.id != %s
                      AND od.create_time >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
                    GROUP BY od.dish_id
                ) t
            """, (dish_id, dish_id))
            row = cursor.fetchone()
            if row and row["category_avg"] is not None:
                val = int(float(row["category_avg"]))
                logger.info(f"cold_start: dish {dish_id} using category_avg={val} (level 2)")
                return {
                    "ts_fallback_level": 2,
                    "fallback_category_avg": val,
                }

            # 级别3：新品初始库存（管理员上架时设定的预期日销量）
            cursor.execute(
                "SELECT new_product_initial_stock, daily_stock FROM pms_dish WHERE id = %s",
                (dish_id,)
            )
            dish = cursor.fetchone()
            if dish and dish.get("new_product_initial_stock") and dish["new_product_initial_stock"] > 0:
                val = dish["new_product_initial_stock"]
                logger.info(f"cold_start: dish {dish_id} using new_product_initial_stock={val} (level 3)")
                return {
                    "ts_fallback_level": 3,
                    "fallback_initial_stock": val,
                }

            # 级别4：daily_stock 作为粗略参考
            if dish and dish.get("daily_stock") and dish["daily_stock"] > 0:
                val = dish["daily_stock"]
                logger.info(f"cold_start: dish {dish_id} using daily_stock={val} (level 4)")
                return {
                    "ts_fallback_level": 4,
                    "fallback_daily_stock": val,
                }
    except Exception as e:
        logger.error(f"cold_start fallback error for dish {dish_id}: {e}")
    finally:
        conn.close()

    # 级别5：绝对兜底
    logger.warning(f"cold_start: dish {dish_id} no fallback data, using absolute minimum (level 5)")
    return {"ts_fallback_level": 5}


def _fallback_base_quantity(fallback: dict) -> Optional[int]:
    """根据冷启动降级级别推导基准量，保证前端「基准量」有值展示"""
    level = fallback.get("ts_fallback_level", 5)
    if level == 2:
        return fallback.get("fallback_category_avg")
    if level == 3:
        return fallback.get("fallback_initial_stock")
    if level == 4:
        return fallback.get("fallback_daily_stock")
    # Level 5：绝对兜底，取 20 份（与 Prompt 规则保持一致）
    return 20


def _ensure_consistent_reasoning(reasoning: str, suggest_quantity: int) -> str:
    """保证推理说明中声明的最终建议量与 JSON 中的 suggest_quantity 完全一致"""
    reasoning = (reasoning or "").strip()
    pattern = re.compile(
        r"(最终建议量|最终建议|最终备菜量|最终备菜|最终推荐量)\s*(为|是|：|:)?\s*\d+\s*份?"
    )
    replaced = pattern.sub(f"最终建议量为 {suggest_quantity} 份", reasoning)
    if replaced != reasoning:
        return replaced
    suffix = f"最终建议量为 {suggest_quantity} 份。"
    return f"{reasoning}。{suffix}" if reasoning else suffix


async def time_series_predict_node(state: PredictState) -> PredictState:
    """时序预测：基于过去30天销量计算基础备菜量及统计参考值"""
    sales = state.get("sales_30d", [])
    dish_id = state.get("dish_id", 0)

    if not sales:
        # 无历史数据 → 走冷启动降级
        logger.warning(f"time_series_predict: no sales data for dish {dish_id}, entering cold start")
        fallback = _cold_start_fallback(dish_id)
        return {
            "time_series_result": None,
            "ts_avg_30d": None,
            "ts_avg_7d": None,
            "ts_median_30d": None,
            # 冷启动时基准量取自降级参考值，否则前端"基准量"将显示为空
            "base_quantity": _fallback_base_quantity(fallback),
            **fallback,
        }

    quantities = [s["quantity"] for s in sales]
    days = len(sales)
    avg_30d = int(sum(quantities) / days)
    median_30d = int(sorted(quantities)[days // 2])

    # 近7日均值
    recent = quantities[-7:] if days >= 7 else quantities
    avg_7d = int(sum(recent) / len(recent))

    # 数据不足7天 → Level 1：有部分数据但不足以信任，仍拉取冷启动参考作为辅助
    if days < 7:
        logger.warning(f"time_series_predict: dish {dish_id} only has {days} days data, insufficient")
        fallback = _cold_start_fallback(dish_id)
        return {
            "time_series_result": avg_7d,
            "ts_avg_30d": avg_30d if days >= 3 else None,
            "ts_avg_7d": avg_7d,
            "ts_median_30d": median_30d if days >= 3 else None,
            "base_quantity": avg_7d,
            **fallback,
            "ts_fallback_level": 1,  # 覆盖冷启动级别：有数据但不足，以已有数据为优先
        }

    result = avg_30d + 5
    logger.info(
        f"time_series_predict: dish {dish_id} "
        f"avg_30d={avg_30d}, avg_7d={avg_7d}, median={median_30d}, result={result}"
    )
    return {
        "time_series_result": result,
        "ts_avg_30d": avg_30d,
        "ts_avg_7d": avg_7d,
        "ts_median_30d": median_30d,
        "ts_fallback_level": 0,
        "base_quantity": result,
    }


async def llm_adjust_node(state: PredictState) -> PredictState:
    """LLM综合调整：结合天气、节假日、评价等因素修正预测量"""
    try:
        llm = ChatOpenAI(
            api_key=settings.llm_api_key,
            base_url=settings.llm_base_url,
            model=settings.llm_model,
            timeout=60,
            # 关闭 DeepSeek 思考模式（默认开启），备菜预测只需确定性 JSON 输出，
            # 关闭后响应显著变快，避免思考链生成耗时导致超时。
            extra_body={"thinking": {"type": "disabled"}},
        )

        prompt_template = _load_prompt("predict_llm_prompt.txt")
        time_series_val = state.get('time_series_result', 0)

        # 将 None 转为 "无" 以便 Prompt 展示
        def _fmt(v):
            return "无" if v is None else str(v)

        prompt = prompt_template.format(
            predict_date=state['predict_date'],
            time_series_result=time_series_val if time_series_val is not None else "无",
            ts_avg_30d=_fmt(state.get('ts_avg_30d')),
            ts_avg_7d=_fmt(state.get('ts_avg_7d')),
            ts_median_30d=_fmt(state.get('ts_median_30d')),
            ts_fallback_level=_fmt(state.get('ts_fallback_level')),
            fallback_category_avg=_fmt(state.get('fallback_category_avg')),
            fallback_initial_stock=_fmt(state.get('fallback_initial_stock')),
            fallback_daily_stock=_fmt(state.get('fallback_daily_stock')),
            weather=state.get('weather'),
            holiday=state.get('holiday'),
            recent_avg_score=state.get('recent_avg_score')
        )

        response = await llm.ainvoke(prompt)
        content = response.content.strip()
        if content.startswith("```json"):
            content = content[7:-3].strip()
        elif content.startswith("```"):
            content = content[3:-3].strip()

        result = json.loads(content)

        suggest_quantity = result.get("suggest_quantity", time_series_val)
        if suggest_quantity is not None:
            # 归一化为整数，保证与落库的 int 字段及推理说明中的数值一致
            suggest_quantity = int(suggest_quantity)
        reasoning = _ensure_consistent_reasoning(
            result.get("reasoning", "LLM adjustment"), suggest_quantity
        )

        return {
            "ai_suggest_quantity": suggest_quantity,
            "reasoning": reasoning,
            "confidence": result.get("confidence", 0.8),
            "final_quantity": suggest_quantity
        }
    except Exception as e:
        logger.error(f"LLM adjustment failed, degrading to time series. Error: {e}")
        fallback_ts = state.get('time_series_result', 0)
        return {
            "ai_suggest_quantity": fallback_ts,
            "reasoning": "LLM failed or timeout, fallback to time series.",
            "confidence": 0.5,
            "final_quantity": fallback_ts
        }


async def save_result_node(state: PredictState) -> PredictState:
    """保存预测结果到数据库"""
    conn = get_db_connection()
    try:
        with conn.cursor() as cursor:
            check_sql = "SELECT id FROM ai_prediction_record WHERE predict_date = %s AND dish_id = %s"
            cursor.execute(check_sql, (state['predict_date'], state['dish_id']))
            exist = cursor.fetchone()

            if exist:
                update_sql = """
                    UPDATE ai_prediction_record
                    SET base_quantity = %s, ai_suggest_quantity = %s, final_quantity = %s,
                        reasoning = %s, confidence = %s, recent_avg_score = %s
                    WHERE id = %s
                """
                cursor.execute(update_sql, (
                    state.get('base_quantity'),
                    state.get('ai_suggest_quantity'),
                    state.get('final_quantity'),
                    state.get('reasoning'),
                    state.get('confidence'),
                    state.get('recent_avg_score'),
                    exist['id']
                ))
            else:
                insert_sql = """
                    INSERT INTO ai_prediction_record
                    (predict_date, dish_id, base_quantity, ai_suggest_quantity, final_quantity, reasoning, confidence, recent_avg_score, status)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 0)
                """
                cursor.execute(insert_sql, (
                    state['predict_date'],
                    state['dish_id'],
                    state.get('base_quantity'),
                    state.get('ai_suggest_quantity'),
                    state.get('final_quantity'),
                    state.get('reasoning'),
                    state.get('confidence'),
                    state.get('recent_avg_score')
                ))
        conn.commit()
    except Exception as e:
        conn.rollback()
        logger.error(f"Error saving prediction result: {e}")
    finally:
        conn.close()
    # 只落库、不修改状态：返回空字典，避免与 llm_adjust 节点写同一键导致 LangGraph 并发更新冲突
    return {}


# 构建备菜预测子图（顺序执行，由触发接口/定时任务直接调用）
builder = StateGraph(PredictState)

builder.add_node("get_sales_30d", get_sales_30d_node)
builder.add_node("get_tomorrow_weather", get_tomorrow_weather_node)
builder.add_node("get_holiday_info", get_holiday_info_node)
builder.add_node("get_recent_reviews", get_recent_reviews_node)
builder.add_node("time_series_predict", time_series_predict_node)
builder.add_node("llm_adjust", llm_adjust_node)
builder.add_node("save_result", save_result_node)

builder.add_edge(START, "get_sales_30d")
builder.add_edge(START, "get_tomorrow_weather")
builder.add_edge(START, "get_holiday_info")
builder.add_edge(START, "get_recent_reviews")

builder.add_edge("get_sales_30d", "time_series_predict")

builder.add_edge("get_tomorrow_weather", "llm_adjust")
builder.add_edge("get_holiday_info", "llm_adjust")
builder.add_edge("get_recent_reviews", "llm_adjust")
builder.add_edge("time_series_predict", "llm_adjust")

builder.add_edge("llm_adjust", "save_result")
builder.add_edge("save_result", END)

predict_subgraph = builder.compile()
