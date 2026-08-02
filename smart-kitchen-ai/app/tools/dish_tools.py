import requests
from langchain_core.tools import tool
from app.services.rag_service import search_knowledge
from app.config import settings
import json


@tool
def search_dish_by_preference(query: str) -> str:
    """
    根据顾客的口味偏好或模糊描述推荐菜品。
    例如：顾客说"想吃点辣的"、"有没有清淡的汤"。
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
    try:
        resp = requests.get(
            f"{settings.java_api_url}/api/proxy/dish/inventory",
            params={"dishName": dish_name},
            timeout=5
        )
        resp.raise_for_status()
        data = resp.json()
        if data.get("code") != 200:
            return f"查询库存失败：{data.get('message', '未知错误')}"
        result = data.get("data")
        if not result:
            return f"未找到名为 {dish_name} 的菜品。"
        if result.get("status") == 0:
            return f"{result['name']} 目前已停售。"
        return f"{result['name']} 当前库存为 {result['dailyStock']} 份。"
    except requests.exceptions.Timeout:
        return f"查询 {dish_name} 库存超时，请稍后再试。"
    except requests.exceptions.ConnectionError:
        return "服务暂不可用，请稍后再试。"
    except Exception as e:
        return f"查询库存时发生错误：{str(e)}"


@tool
def get_dish_ingredients(dish_name: str) -> str:
    """
    查询指定菜品的配料和过敏原信息。
    必须传入准确的菜品名称。
    """
    try:
        resp = requests.get(
            f"{settings.java_api_url}/api/proxy/dish/ingredients",
            params={"dishName": dish_name},
            timeout=5
        )
        resp.raise_for_status()
        data = resp.json()
        if data.get("code") != 200:
            return f"查询配料失败：{data.get('message', '未知错误')}"
        result = data.get("data")
        if not result:
            return f"未找到名为 {dish_name} 的菜品。"
        
        ingredients_raw = result.get("ingredients")
        if not ingredients_raw:
            return f"{result['name']} 暂无详细配料信息。"
        try:
            ingredients = json.loads(ingredients_raw) if isinstance(ingredients_raw, str) else ingredients_raw
            return f"{result['name']} 的配料信息：{', '.join(ingredients)}"
        except Exception:
            return f"{result['name']} 的配料信息：{ingredients_raw}"
    except requests.exceptions.Timeout:
        return f"查询 {dish_name} 配料超时，请稍后再试。"
    except requests.exceptions.ConnectionError:
        return "服务暂不可用，请稍后再试。"
    except Exception as e:
        return f"查询配料时发生错误：{str(e)}"
