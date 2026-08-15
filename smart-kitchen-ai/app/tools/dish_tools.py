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


def _parse_json_list(value):
    """
    将接口返回的 JSON 数组字符串解析为 Python 列表。
    兼容历史纯文本数据：解析失败或为空时返回 None，由调用方原样展示。
    """
    if not value or value == '[]':
        return None
    if isinstance(value, list):
        return value or None
    try:
        parsed = json.loads(value)
        if isinstance(parsed, list) and parsed:
            return parsed
    except Exception:
        pass
    return None


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

        name = result.get("name")
        ingredients = _parse_json_list(result.get("ingredients"))
        allergens = _parse_json_list(result.get("allergens"))

        parts = []
        if ingredients:
            parts.append(f"配料：{'、'.join(ingredients)}")
        elif result.get("ingredients") and result.get("ingredients") != '[]':
            parts.append(f"配料：{result.get('ingredients')}")
        if allergens:
            parts.append(f"过敏原：{'、'.join(allergens)}")
        elif result.get("allergens") and result.get("allergens") != '[]':
            parts.append(f"过敏原：{result.get('allergens')}")

        if not parts:
            return f"{name} 暂无详细配料信息。"
        return f"{name} 的配料信息：{'；'.join(parts)}"
    except requests.exceptions.Timeout:
        return f"查询 {dish_name} 配料超时，请稍后再试。"
    except requests.exceptions.ConnectionError:
        return "服务暂不可用，请稍后再试。"
    except Exception as e:
        return f"查询配料时发生错误：{str(e)}"
