from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)

def test_get_prediction_results():
    response = client.get("/ai/predict/result?target_date=2026-07-29")
    assert response.status_code == 200
    data = response.json()
    assert "data" in data
    assert isinstance(data["data"], list)

def test_trigger_and_confirm():
    # 简化测试，只测接口响应
    response = client.post("/ai/predict/confirm", json={
        "record_id": 999999,
        "final_quantity": 30,
        "confirmed_by": 1
    })
    # 因为 999999 不存在，会返回400
    assert response.status_code == 400
