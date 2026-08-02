from app.db.mysql_client import get_db_connection
import datetime

def get_sales_30d(dish_id: int) -> list:
    """获取过去30天的销量数据"""
    conn = get_db_connection()
    sales = []
    try:
        with conn.cursor() as cursor:
            sql = """
                SELECT DATE(create_time) as sale_date, SUM(quantity) as total_quantity
                FROM oms_order_detail
                WHERE dish_id = %s
                  AND create_time >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
                GROUP BY DATE(create_time)
                ORDER BY sale_date
            """
            cursor.execute(sql, (dish_id,))
            result = cursor.fetchall()
            for row in result:
                sales.append({
                    "date": row["sale_date"].strftime("%Y-%m-%d") if hasattr(row["sale_date"], 'strftime') else str(row["sale_date"]),
                    "quantity": int(row["total_quantity"])
                })
    except Exception as e:
        print(f"Error getting sales_30d: {e}")
    finally:
        conn.close()
    return sales

def get_recent_reviews(dish_id: int) -> dict:
    """获取近期评价"""
    conn = get_db_connection()
    result = {"avg_score": 5.0, "reviews": []}
    try:
        with conn.cursor() as cursor:
            sql = """
                SELECT r.score, r.comment, r.create_time
                FROM oms_review r
                JOIN oms_order_detail od ON r.order_id = od.order_id
                WHERE od.dish_id = %s
                ORDER BY r.create_time DESC
                LIMIT 10
            """
            cursor.execute(sql, (dish_id,))
            rows = cursor.fetchall()
            if rows:
                result["reviews"] = [
                    {"score": float(r["score"]), "comment": r["comment"]}
                    for r in rows
                ]
                result["avg_score"] = sum(r["score"] for r in rows) / len(rows)
    except Exception as e:
        print(f"Error getting recent reviews: {e}")
    finally:
        conn.close()
    return result
