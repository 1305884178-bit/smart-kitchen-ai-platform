import asyncio
import json
import logging
from typing import Optional, List, Dict
from datetime import datetime, timedelta
from app.agents.predict_agent import predict_subgraph
from app.db.mysql_client import get_db_connection
from app.db.redis_client import redis_client
from app.utils.mcp_client import call_mcp_tool

logger = logging.getLogger(__name__)

# 数据归属约定：ai_prediction_record 的 INSERT/UPDATE 全部在 Java
# （内部接口 /api/proxy/predict/upsert 与人工确认 /api/admin/predict/confirm）。
# Python 侧对该表只读（get_prediction_results）；任务进度仍由 Python 写 Redis predict:task:*。

TASK_KEY_PREFIX = "predict:task:"
TASK_TTL_SECONDS = 3600
# 限制同一批任务中同时运行的菜品图数量，保护 LLM 与外部依赖。
PREDICTION_CONCURRENCY = 5


def _set_task_status(task_id: str, status: Dict) -> None:
    """将预测任务状态写入 Redis（1小时过期）"""
    try:
        redis_client.set(f"{TASK_KEY_PREFIX}{task_id}", json.dumps(status), ex=TASK_TTL_SECONDS)
    except Exception as e:
        logger.error(f"Error setting task status: {e}")


def get_task_status(task_id: str) -> Optional[Dict]:
    """从 Redis 读取预测任务状态"""
    try:
        raw = redis_client.get(f"{TASK_KEY_PREFIX}{task_id}")
        if raw:
            return json.loads(raw)
    except Exception as e:
        logger.error(f"Error getting task status: {e}")
    return None


async def _fetch_shared_context(target_date: str) -> Dict[str, Dict]:
    """
    天气/节假日只与 predict_date 有关、与菜品无关：整个预测任务只拉取一次，
    注入每道菜的初始 state（图中对应节点检测到已注入会短路，不再重复调外部 API）。
    失败时注入空 dict（等价于该维度缺失，Prompt 中按缺失处理），不阻塞任务。
    """
    try:
        weather = await call_mcp_tool("weather_server", "get_tomorrow_weather", {"date": target_date})
    except Exception as e:
        logger.error(f"fetch shared weather failed: {e}")
        weather = {}
    try:
        holiday = await call_mcp_tool("holiday_server", "get_holiday_info", {"date": target_date})
    except Exception as e:
        logger.error(f"fetch shared holiday failed: {e}")
        holiday = {}
    return {"weather": weather, "holiday": holiday}


async def trigger_prediction(target_date: Optional[str] = None, dish_id: Optional[int] = None, task_id: Optional[str] = None) -> Dict:
    """触发预测任务，task_id 存在时同步更新任务进度"""
    if not target_date:
        target_date = (datetime.now() + timedelta(days=1)).strftime("%Y-%m-%d")
        
    conn = get_db_connection()
    dishes = []
    try:
        with conn.cursor() as cursor:
            if dish_id:
                sql = "SELECT id as dish_id FROM pms_dish WHERE id = %s AND status = 1"
                cursor.execute(sql, (dish_id,))
            else:
                sql = "SELECT id as dish_id FROM pms_dish WHERE status = 1"
                cursor.execute(sql)
            rows = cursor.fetchall()
            dishes = [row["dish_id"] for row in rows]
    except Exception as e:
        logger.error(f"Error getting dishes: {e}")
    finally:
        conn.close()

    if not dishes:
        if task_id:
            _set_task_status(task_id, {"status": "error", "total": 0, "done": 0, "message": "没有找到可预测的在售菜品"})
        return {"status": "error", "message": "No active dishes found for prediction."}

    logger.info(f"Triggering prediction for {len(dishes)} dishes on {target_date}")
    total = len(dishes)
    if task_id:
        _set_task_status(task_id, {"status": "running", "total": total, "done": 0, "message": f"正在预测 {total} 道菜品..."})

    # 天气/节假日与菜品无关，整个任务只拉取一次，注入每道菜的初始 state
    shared_context = await _fetch_shared_context(target_date)
    done_count = 0
    # 一个共享信号量管理整批菜品；每道菜各自创建信号量无法起到限流作用。
    prediction_semaphore = asyncio.Semaphore(PREDICTION_CONCURRENCY)

    async def run_one(d_id: int):
        """执行单道菜品预测并在完成后推进进度"""
        nonlocal done_count
        try:
            async with prediction_semaphore:
                state = {
                    "predict_date": target_date,
                    "dish_id": d_id,
                    **shared_context,
                }
                return await predict_subgraph.ainvoke(state)
        finally:
            done_count += 1
            if task_id:
                _set_task_status(task_id, {
                    "status": "running",
                    "total": total,
                    "done": done_count,
                    "message": f"已完成 {done_count}/{total} 道菜品"
                })

    # 异步并发执行预测（直接调用预测子图）
    results = await asyncio.gather(*(run_one(d) for d in dishes), return_exceptions=True)
    
    success_count = sum(1 for r in results if not isinstance(r, Exception))
    failed_count = total - success_count

    if task_id:
        _set_task_status(task_id, {
            "status": "success" if failed_count == 0 else "error",
            "total": total,
            "done": total,
            "success_count": success_count,
            "failed_count": failed_count,
            "message": f"预测完成：成功 {success_count} 道，失败 {failed_count} 道"
        })

    return {
        "status": "success", 
        "message": f"Triggered prediction for {total} dishes.",
        "success_count": success_count,
        "failed_count": failed_count
    }

def get_prediction_results(target_date: str) -> List[Dict]:
    """获取预测结果（只读；管理台查询已改由 Java 直查，本函数仅供 Python 内部/调试用）"""
    conn = get_db_connection()
    results = []
    try:
        with conn.cursor() as cursor:
            sql = """
                SELECT r.*, d.name as dish_name 
                FROM ai_prediction_record r
                JOIN pms_dish d ON r.dish_id = d.id
                WHERE r.predict_date = %s
                ORDER BY r.create_time DESC
            """
            cursor.execute(sql, (target_date,))
            rows = cursor.fetchall()
            for row in rows:
                results.append({
                    "id": row["id"],
                    "predict_date": str(row["predict_date"]),
                    "dish_id": row["dish_id"],
                    "dish_name": row["dish_name"],
                    "base_quantity": row["base_quantity"],
                    "ai_suggest_quantity": row["ai_suggest_quantity"],
                    "final_quantity": row["final_quantity"],
                    "reasoning": row["reasoning"],
                    "confidence": float(row["confidence"]) if row["confidence"] else None,
                    "recent_avg_score": float(row["recent_avg_score"]) if row["recent_avg_score"] else None,
                    "status": row["status"],
                    "confirmed_by": row["confirmed_by"],
                    "create_time": str(row["create_time"])
                })
    except Exception as e:
        logger.error(f"Error getting prediction results: {e}")
    finally:
        conn.close()
    return results
