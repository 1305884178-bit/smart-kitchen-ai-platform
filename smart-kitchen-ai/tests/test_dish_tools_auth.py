"""
dish_tools 调 Java /api/proxy/** 的内部 token 头测试（HTTP 全 mock，离线可跑）。

约定：配置 AI_INTERNAL_TOKEN 后 check_dish_inventory / get_dish_ingredients
必须携带 Authorization: Bearer <token>；未配置时不带头（本地联调）。
"""
import asyncio
from unittest.mock import MagicMock, patch

from app.config import settings
from app.tools import dish_tools


def _ok_response(data):
    resp = MagicMock()
    resp.json.return_value = {"code": 200, "data": data}
    resp.raise_for_status.return_value = None
    return resp


class _FakeAsyncClient:
    def __init__(self, response):
        self.response = response
        self.get_calls = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    async def get(self, *args, **kwargs):
        self.get_calls.append((args, kwargs))
        return self.response


def _invoke(tool, payload):
    return asyncio.run(tool.ainvoke(payload))


class TestInternalTokenHeader:
    def test_inventory_sends_bearer_when_token_configured(self):
        fake_client = _FakeAsyncClient(_ok_response({"name": "水煮鱼", "dailyStock": 8, "status": 1}))
        with patch.object(dish_tools.httpx, "AsyncClient", return_value=fake_client), \
             patch.object(settings, "ai_internal_token", "internal-test-token"):
            out = _invoke(dish_tools.check_dish_inventory, {"dish_name": "水煮鱼"})

        assert "库存为 8 份" in out
        assert fake_client.get_calls[0][1]["headers"]["Authorization"] == "Bearer internal-test-token"

    def test_ingredients_sends_bearer_when_token_configured(self):
        fake_client = _FakeAsyncClient(_ok_response({"name": "水煮鱼", "ingredients": '["草鱼"]', "allergens": '["鱼"]'}))
        with patch.object(dish_tools.httpx, "AsyncClient", return_value=fake_client), \
             patch.object(settings, "ai_internal_token", "internal-test-token"):
            out = _invoke(dish_tools.get_dish_ingredients, {"dish_name": "水煮鱼"})

        assert "草鱼" in out
        assert fake_client.get_calls[0][1]["headers"]["Authorization"] == "Bearer internal-test-token"

    def test_no_header_when_token_not_configured(self):
        fake_client = _FakeAsyncClient(_ok_response({"name": "水煮鱼", "dailyStock": 8, "status": 1}))
        with patch.object(dish_tools.httpx, "AsyncClient", return_value=fake_client), \
             patch.object(settings, "ai_internal_token", ""):
            _invoke(dish_tools.check_dish_inventory, {"dish_name": "水煮鱼"})

        assert "Authorization" not in fake_client.get_calls[0][1]["headers"]
