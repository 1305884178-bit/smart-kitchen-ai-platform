from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)

def test_process_and_search():
    # 1. Process document
    process_response = client.post(
        "/ai/knowledge/process",
        json={
            "content": "This is a test document about kitchen safety. Always wash hands before cooking.",
            "version": "1.0",
            "status": "active"
        }
    )
    assert process_response.status_code == 200
    data = process_response.json()
    assert data["success"] is True
    assert "document_id" in data
    assert data["chunk_count"] > 0
    
    # 2. Search document
    search_response = client.post(
        "/ai/knowledge/search",
        json={
            "query": "kitchen safety",
            "top_k": 3,
            "version": "1.0"
        }
    )
    assert search_response.status_code == 200
    search_data = search_response.json()
    assert "results" in search_data
    assert len(search_data["results"]) > 0
    assert search_data["results"][0]["version"] == "1.0"

if __name__ == "__main__":
    test_process_and_search()
    print("All tests passed!")
