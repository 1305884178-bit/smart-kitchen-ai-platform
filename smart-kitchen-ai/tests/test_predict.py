from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)


def test_get_prediction_results():
    response = client.get("/ai/predict/result?target_date=2026-07-29")
    assert response.status_code == 200
    data = response.json()
    assert "data" in data
    assert isinstance(data["data"], list)


def test_confirm_endpoint_deprecated():
    # 人工确认写入口已收归 Java（/api/admin/predict/confirm），Python 侧返回 410
    response = client.post("/ai/predict/confirm", json={
        "record_id": 999999,
        "final_quantity": 30,
        "confirmed_by": 1
    })
    assert response.status_code == 410


def test_save_result_node_calls_java_upsert():
    """save_result_node 不再直写 MySQL，改为 HTTP 调 Java 内部接口并带内部 token"""
    import asyncio
    from app.agents import predict_agent

    state = {
        "predict_date": "2026-08-10",
        "dish_id": 1,
        "base_quantity": 55,
        "ai_suggest_quantity": 60,
        "final_quantity": 60,
        "reasoning": "unit test",
        "confidence": 0.8,
        "recent_avg_score": 4.5,
    }
    mock_requests = MagicMock()
    mock_requests.post.return_value.json.return_value = {"code": 200}
    with patch.object(predict_agent, "requests", mock_requests), \
         patch.object(predict_agent.settings, "ai_internal_token", "internal-test-token"):
        result = asyncio.run(predict_agent.save_result_node(state))

    # 只落库不改状态
    assert result == {}
    args, kwargs = mock_requests.post.call_args
    assert args[0].endswith("/api/proxy/predict/upsert")
    assert kwargs["headers"]["Authorization"] == "Bearer internal-test-token"
    body = kwargs["json"]
    assert body["predict_date"] == "2026-08-10"
    assert body["dish_id"] == 1
    assert body["ai_suggest_quantity"] == 60


def test_save_result_node_failure_only_logs():
    """Java 落库失败只打日志，不中断预测工作流"""
    import asyncio
    from app.agents import predict_agent

    state = {"predict_date": "2026-08-10", "dish_id": 1}
    mock_requests = MagicMock()
    mock_requests.post.side_effect = RuntimeError("connection refused")
    with patch.object(predict_agent, "requests", mock_requests):
        # 不应抛异常
        result = asyncio.run(predict_agent.save_result_node(state))
    assert result == {}
