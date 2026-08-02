import asyncio
import logging
from typing import Optional, List, Dict
from datetime import datetime, timedelta
from app.agents.supervisor import supervisor
from app.db.mysql_client import get_db_connection

logger = logging.getLogger(__name__)

async def trigger_prediction(target_date: Optional[str] = None, dish_id: Optional[int] = None) -> Dict:
    """触发预测任务"""
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
        return {"status": "error", "message": "No active dishes found for prediction."}

    logger.info(f"Triggering prediction for {len(dishes)} dishes on {target_date}")
    
    # 异步并发执行预测（通过 Supervisor 路由到预测子图）
    tasks = []
    for d_id in dishes:
        state = {
            "task_type": "predict",
            "predict_date": target_date,
            "dish_id": d_id
        }
        tasks.append(supervisor.ainvoke(state))
        
    results = await asyncio.gather(*tasks, return_exceptions=True)
    
    success_count = sum(1 for r in results if not isinstance(r, Exception))
    
    return {
        "status": "success", 
        "message": f"Triggered prediction for {len(dishes)} dishes.",
        "success_count": success_count,
        "failed_count": len(dishes) - success_count
    }

def get_prediction_results(target_date: str) -> List[Dict]:
    """获取预测结果"""
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

def confirm_prediction(record_id: int, final_quantity: int, confirmed_by: int) -> bool:
    """确认/覆盖预测结果"""
    conn = get_db_connection()
    success = False
    try:
        with conn.cursor() as cursor:
            sql = """
                UPDATE ai_prediction_record
                SET final_quantity = %s, status = 1, confirmed_by = %s
                WHERE id = %s
            """
            cursor.execute(sql, (final_quantity, confirmed_by, record_id))
            if cursor.rowcount > 0:
                success = True
        conn.commit()
    except Exception as e:
        conn.rollback()
        logger.error(f"Error confirming prediction: {e}")
    finally:
        conn.close()
    return success
