from unittest.mock import AsyncMock, MagicMock, patch

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
    mock_response = MagicMock()
    mock_response.json.return_value = {"code": 200}
    mock_client = MagicMock()
    mock_client.post = AsyncMock(return_value=mock_response)
    mock_async_client = MagicMock()
    mock_async_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_async_client.__aexit__ = AsyncMock(return_value=None)
    with patch.object(predict_agent.httpx, "AsyncClient", return_value=mock_async_client), \
         patch.object(predict_agent.settings, "ai_internal_token", "internal-test-token"):
        result = asyncio.run(predict_agent.save_result_node(state))

    # 只落库不改状态
    assert result == {}
    args, kwargs = mock_client.post.await_args
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
    mock_client = MagicMock()
    mock_client.post = AsyncMock(side_effect=RuntimeError("connection refused"))
    mock_async_client = MagicMock()
    mock_async_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_async_client.__aexit__ = AsyncMock(return_value=None)
    with patch.object(predict_agent.httpx, "AsyncClient", return_value=mock_async_client):
        # 不应抛异常
        result = asyncio.run(predict_agent.save_result_node(state))
    assert result == {}


def test_llm_adjust_node_uses_structured_output():
    """LLM 结果应经 Pydantic schema 校验，不再手工解析 JSON 字符串。"""
    import asyncio
    from app.agents import predict_agent

    structured_llm = MagicMock()
    structured_llm.ainvoke = AsyncMock(return_value=predict_agent.PredictionAdjustment(
        suggest_quantity=60,
        reasoning="天气适宜，维持基准量附近。",
        confidence=0.85,
    ))
    llm = MagicMock()
    llm.with_structured_output.return_value = structured_llm
    state = {
        "predict_date": "2026-08-10",
        "dish_id": 1,
        "time_series_result": 55,
        "base_quantity": 55,
        "weather": {},
        "holiday": {},
        "recent_avg_score": 5.0,
    }
    with patch.object(predict_agent, "ChatOpenAI", return_value=llm), \
         patch.object(predict_agent, "_load_prompt", return_value="预测提示词"):
        result = asyncio.run(predict_agent.llm_adjust_node(state))

    llm.with_structured_output.assert_called_once_with(
        predict_agent.PredictionAdjustment,
        method="function_calling",
    )
    assert result["ai_suggest_quantity"] == 60
    assert result["confidence"] == 0.85
    assert "最终建议量为 60 份" in result["reasoning"]
