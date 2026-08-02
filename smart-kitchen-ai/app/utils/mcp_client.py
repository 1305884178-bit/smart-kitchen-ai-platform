import logging

from app.tools.weather_tools import get_tomorrow_weather as _get_weather
from app.tools.holiday_tools import get_holiday_info as _get_holiday

logger = logging.getLogger(__name__)


async def call_mcp_tool(server_name: str, tool_name: str, args: dict) -> dict:
    """MCP 工具调度：根据 server_name + tool_name 路由到实际实现"""
    logger.info(f"Calling MCP Tool: {server_name}.{tool_name} with args: {args}")

    if server_name == "weather_server" and tool_name == "get_tomorrow_weather":
        return await _get_weather(args.get("date", ""))

    if server_name == "holiday_server" and tool_name == "get_holiday_info":
        return await _get_holiday(args.get("date", ""))

    logger.warning(f"Unknown MCP tool: {server_name}.{tool_name}")
    return {}
