"""
dish_tools 调 Java /api/proxy/** 的内部 token 头测试（HTTP 全 mock，离线可跑）。

约定：配置 AI_INTERNAL_TOKEN 后 check_dish_inventory / get_dish_ingredients
必须携带 Authorization: Bearer <token>；未配置时不带头（本地联调）。
"""
from unittest.mock import MagicMock, patch

from app.config import settings
from app.tools import dish_tools


def _ok_response(data):
    resp = MagicMock()
    resp.json.return_value = {"code": 200, "data": data}
    resp.raise_for_status.return_value = None
    return resp


class TestInternalTokenHeader:
    def test_inventory_sends_bearer_when_token_configured(self):
        mock_requests = MagicMock()
        mock_requests.get.return_value = _ok_response(
            {"name": "水煮鱼", "dailyStock": 8, "status": 1}
        )
        mock_requests.exceptions = dish_tools.requests.exceptions
        with patch.object(dish_tools, "requests", mock_requests), \
             patch.object(settings, "ai_internal_token", "internal-test-token"):
            out = dish_tools.check_dish_inventory.invoke({"dish_name": "水煮鱼"})

        assert "库存为 8 份" in out
        _, kwargs = mock_requests.get.call_args
        assert kwargs["headers"]["Authorization"] == "Bearer internal-test-token"

    def test_ingredients_sends_bearer_when_token_configured(self):
        mock_requests = MagicMock()
        mock_requests.get.return_value = _ok_response(
            {"name": "水煮鱼", "ingredients": '["草鱼"]', "allergens": '["鱼"]'}
        )
        mock_requests.exceptions = dish_tools.requests.exceptions
        with patch.object(dish_tools, "requests", mock_requests), \
             patch.object(settings, "ai_internal_token", "internal-test-token"):
            out = dish_tools.get_dish_ingredients.invoke({"dish_name": "水煮鱼"})

        assert "草鱼" in out
        _, kwargs = mock_requests.get.call_args
        assert kwargs["headers"]["Authorization"] == "Bearer internal-test-token"

    def test_no_header_when_token_not_configured(self):
        mock_requests = MagicMock()
        mock_requests.get.return_value = _ok_response(
            {"name": "水煮鱼", "dailyStock": 8, "status": 1}
        )
        mock_requests.exceptions = dish_tools.requests.exceptions
        with patch.object(dish_tools, "requests", mock_requests), \
             patch.object(settings, "ai_internal_token", ""):
            dish_tools.check_dish_inventory.invoke({"dish_name": "水煮鱼"})

        _, kwargs = mock_requests.get.call_args
        assert "Authorization" not in kwargs["headers"]
