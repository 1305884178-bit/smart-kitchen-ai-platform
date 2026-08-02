import httpx
import logging
from datetime import datetime

from app.config import settings

logger = logging.getLogger(__name__)


async def get_tomorrow_weather(date: str) -> dict:
    """调用 OpenWeatherMap 获取指定日期的天气信息"""
    try:
        tomorrow = datetime.strptime(date, "%Y-%m-%d").date()
    except ValueError:
        logger.error(f"Invalid date format: {date}")
        return {"error": "日期格式错误"}

    target_date_str = tomorrow.strftime("%Y-%m-%d")

    async with httpx.AsyncClient(timeout=10) as client:
        resp = await client.get(
            "https://api.openweathermap.org/data/2.5/forecast",
            params={
                "q": f"{settings.weather_city},CN",
                "appid": settings.weather_api_key,
                "units": "metric",
                "lang": "zh_cn",
            },
        )
        resp.raise_for_status()
        data = resp.json()

    if data.get("cod") != "200":
        logger.error(f"OpenWeatherMap API error: {data}")
        return {"error": f"天气API错误: {data.get('message', '未知错误')}"}

    forecast_items = data.get("list", [])
    tomorrow_items = [
        item for item in forecast_items
        if item.get("dt_txt", "").startswith(target_date_str)
    ]

    if not tomorrow_items:
        logger.warning(f"No forecast data for {target_date_str}")
        return {
            "temperature": "N/A",
            "condition": "无数据",
            "wind": "N/A",
            "humidity": "N/A",
            "desc": f"{target_date_str} 无天气预报数据",
        }

    temps = [item["main"]["temp"] for item in tomorrow_items]
    humidities = [item["main"]["humidity"] for item in tomorrow_items]
    winds = [item["wind"]["speed"] for item in tomorrow_items]
    conditions = [item["weather"][0]["description"] for item in tomorrow_items]

    avg_humidity = sum(humidities) / len(humidities)
    avg_wind = sum(winds) / len(winds)
    main_condition = max(set(conditions), key=conditions.count)
    max_temp = max(temps)
    min_temp = min(temps)

    result = {
        "temperature": f"{min_temp:.0f}°C ~ {max_temp:.0f}°C",
        "condition": main_condition,
        "wind": f"{avg_wind:.1f}m/s",
        "humidity": f"{avg_humidity:.0f}%",
        "desc": f"{target_date_str} {settings.weather_city}天气：{main_condition}，温度{min_temp:.0f}~{max_temp:.0f}°C",
    }

    logger.info(f"Weather result for {target_date_str}: {result}")
    return result
