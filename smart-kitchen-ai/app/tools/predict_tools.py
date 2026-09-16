from app.db.mysql_client import get_db_connection
import datetime

def get_sales_30d(dish_id: int, predict_date: str) -> list:
    """
    获取历史日销量（营业日口径），供时序预测使用。

    口径约定：
    - 时间窗锚在「昨天」：[CURDATE()-30, CURDATE())，不含尚未完结的今天，
      避免把半日销量当全天样本（定时 02:00 / 管理员白天手动触发行为一致）；
    - 营业判定：当天全店任一菜品有订单明细即视为营业日；
      营业但该菜未售出记 0，未营业日视为 null（剔除，不进分母）；
    - 菜品上架日（pms_dish.create_time）之前的日期视为 null（不在售），剔除；
    - 周末/工作日拆分：仅保留与 predict_date 同类型（周六日 / 周一~周五）的日期；
    - 窗口内该菜从未售出（dish_qty 为空）时返回 []，由时序节点走冷启动降级，
      避免新品被 0 填充成「均值 0、基准 5」而绕过冷启动链。
    """
    try:
        target = datetime.datetime.strptime(predict_date, "%Y-%m-%d").date()
        target_is_weekend = target.weekday() >= 5  # 周六=5、周日=6
    except (ValueError, TypeError):
        target_is_weekend = None  # 日期非法时不做类型过滤，退化为全部营业日

    conn = get_db_connection()
    sales = []
    try:
        with conn.cursor() as cursor:
            # 1) 窗口内该菜日销量（有单日）
            cursor.execute("""
                SELECT DATE(create_time) as sale_date, SUM(quantity) as total_quantity
                FROM oms_order_detail
                WHERE dish_id = %s
                  AND create_time >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
                  AND create_time < CURDATE()
                GROUP BY DATE(create_time)
            """, (dish_id,))
            dish_qty = {
                row["sale_date"]: int(row["total_quantity"])
                for row in cursor.fetchall()
            }
            if not dish_qty:
                return []

            # 2) 窗口内营业日（全店任一菜品有订单明细即营业）
            cursor.execute("""
                SELECT DISTINCT DATE(create_time) as biz_date
                FROM oms_order_detail
                WHERE create_time >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
                  AND create_time < CURDATE()
            """)
            open_days = [row["biz_date"] for row in cursor.fetchall()]

            # 3) 菜品上架时间：上架前日期视为不在售（null）
            cursor.execute("SELECT create_time FROM pms_dish WHERE id = %s", (dish_id,))
            dish = cursor.fetchone()
    except Exception as e:
        print(f"Error getting sales_30d: {e}")
        return []
    finally:
        conn.close()

    listed_since = None
    if dish and dish.get("create_time"):
        ct = dish["create_time"]
        listed_since = ct.date() if isinstance(ct, datetime.datetime) else ct

    for d in sorted(open_days):
        if isinstance(d, datetime.datetime):
            d = d.date()
        if listed_since and d < listed_since:
            continue  # 上架前：null，不计入分母
        if target_is_weekend is not None and (d.weekday() >= 5) != target_is_weekend:
            continue  # 只保留与预测日同类型（周末/工作日）的营业日
        sales.append({
            "date": d.strftime("%Y-%m-%d"),
            "quantity": dish_qty.get(d, 0),  # 营业但未售出记 0
        })
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
