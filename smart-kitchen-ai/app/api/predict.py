from fastapi import APIRouter, BackgroundTasks, HTTPException
from pydantic import BaseModel
from typing import Optional
from app.services.predict_service import trigger_prediction, get_prediction_results, confirm_prediction

router = APIRouter(prefix="/ai/predict", tags=["Predict"])

class TriggerRequest(BaseModel):
    target_date: Optional[str] = None
    dish_id: Optional[int] = None

class ConfirmRequest(BaseModel):
    record_id: int
    final_quantity: int
    confirmed_by: int

@router.post("/trigger")
async def trigger_prediction_api(request: TriggerRequest, background_tasks: BackgroundTasks):
    """手动触发备菜预测"""
    # 由于预测可能需要时间，放入后台任务或直接异步执行。这里直接执行，前端可以等或者做成异步任务。
    # 为了防止接口超时，可以放入 background_tasks，但根据需求如果是批量这里可能会慢
    # 这里直接 await
    result = await trigger_prediction(request.target_date, request.dish_id)
    if result["status"] == "error":
        raise HTTPException(status_code=400, detail=result["message"])
    return result

@router.get("/result")
async def get_prediction_results_api(target_date: str):
    """获取指定日期的预测结果"""
    results = get_prediction_results(target_date)
    return {"code": 200, "data": results}

@router.post("/confirm")
async def confirm_prediction_api(request: ConfirmRequest):
    """确认/覆盖预测量"""
    success = confirm_prediction(request.record_id, request.final_quantity, request.confirmed_by)
    if success:
        return {"code": 200, "message": "Confirm success"}
    else:
        raise HTTPException(status_code=400, detail="Confirm failed, record not found or update error")
