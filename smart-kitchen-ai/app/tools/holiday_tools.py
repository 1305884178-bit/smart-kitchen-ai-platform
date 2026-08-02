import httpx
import json
import logging

logger = logging.getLogger(__name__)

HOLIDAY_MCP_URL = "https://mcp.api-inference.modelscope.net/5b7bf983dbc746/mcp"


async def get_holiday_info(date: str) -> dict:
    """调用 ModelScope MCP 查询指定日期是否为节假日"""
    async with httpx.AsyncClient(timeout=15) as client:
        try:
            init_payload = {
                "jsonrpc": "2.0",
                "id": 0,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "smart-kitchen-ai", "version": "1.0.0"},
                },
            }
            init_resp = await client.post(HOLIDAY_MCP_URL, json=init_payload)
            init_resp.raise_for_status()
            session_id = init_resp.headers.get("Mcp-Session-Id")
            headers = {"Content-Type": "application/json"}
            if session_id:
                headers["Mcp-Session-Id"] = session_id

            call_payload = {
                "jsonrpc": "2.0",
                "id": 2,
                "method": "tools/call",
                "params": {
                    "name": "holiday_info",
                    "arguments": {"date": date},
                },
            }
            call_resp = await client.post(HOLIDAY_MCP_URL, json=call_payload, headers=headers)
            call_data = call_resp.json()

            result = call_data.get("result", {})
            content = result.get("content", [])
            if content:
                text = content[0].get("text", "{}")
                parsed = json.loads(text)
                logger.info(f"Holiday result for {date}: {parsed}")
                return parsed

            logger.warning(f"Unexpected MCP response: {call_data}")
            return {"is_holiday": False, "name": "未知", "desc": "节假日数据解析失败"}

        except Exception as e:
            logger.error(f"Holiday MCP call failed: {e}")
            return {"is_holiday": False, "name": "未知", "desc": f"节假日查询异常: {str(e)}"}
