from langchain_core.tools import tool
from app.db.mysql_client import get_db_connection
from app.services.rag_service import search_knowledge
import json

@tool
def search_dish_by_preference(query: str) -> str:
    """
    根据顾客的口味偏好或模糊描述推荐菜品。
    例如：顾客说“想吃点辣的”、“有没有清淡的汤”。
    """
    results = search_knowledge(query, top_k=3)
    if not results:
        return "抱歉，没有找到符合您口味的菜品推荐。"
    
    recommendations = []
    for idx, res in enumerate(results):
        recommendations.append(f"{idx+1}. {res['text']}")
    return "根据您的口味偏好，我推荐：\n" + "\n".join(recommendations)

@tool
def check_dish_inventory(dish_name: str) -> str:
    """
    查询指定菜品的实时库存。
    必须传入准确的菜品名称。
    """
    conn = get_db_connection()
    try:
        with conn.cursor() as cursor:
            sql = "SELECT name, daily_stock, status FROM pms_dish WHERE name LIKE %s LIMIT 1"
            cursor.execute(sql, (f"%{dish_name}%",))
            result = cursor.fetchone()
            if not result:
                return f"未找到名为 {dish_name} 的菜品。"
            if result['status'] == 0:
                return f"{result['name']} 目前已停售。"
            return f"{result['name']} 当前库存为 {result['daily_stock']} 份。"
    finally:
        conn.close()

@tool
def get_dish_ingredients(dish_name: str) -> str:
    """
    查询指定菜品的配料和过敏原信息。
    必须传入准确的菜品名称。
    """
    conn = get_db_connection()
    try:
        with conn.cursor() as cursor:
            sql = "SELECT name, ingredients FROM pms_dish WHERE name LIKE %s LIMIT 1"
            cursor.execute(sql, (f"%{dish_name}%",))
            result = cursor.fetchone()
            if not result:
                return f"未找到名为 {dish_name} 的菜品。"
            
            ingredients_raw = result['ingredients']
            if not ingredients_raw:
                return f"{result['name']} 暂无详细配料信息。"
            
            try:
                ingredients = json.loads(ingredients_raw)
                return f"{result['name']} 的配料信息：{', '.join(ingredients)}"
            except Exception:
                return f"{result['name']} 的配料信息：{ingredients_raw}"
    finally:
        conn.close()
