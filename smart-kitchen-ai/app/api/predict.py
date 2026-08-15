import uuid

from fastapi import APIRouter, BackgroundTasks, HTTPException
from pydantic import BaseModel
from typing import Optional
from app.services.predict_service import trigger_prediction, get_prediction_results, confirm_prediction, get_task_status

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
    """手动触发备菜预测（异步执行，立即返回任务ID）"""
    # 预测流程涉及多道菜品的 LLM 与 MCP 调用，耗时较长；
    # 交给后台任务异步执行，接口立即返回，避免调用方（Java 代理）读超时。
    task_id = uuid.uuid4().hex
    background_tasks.add_task(trigger_prediction, request.target_date, request.dish_id, task_id)
    return {"task_id": task_id, "status": "running"}

@router.get("/status")
async def get_prediction_status_api(task_id: str):
    """查询预测任务状态"""
    status = get_task_status(task_id)
    if status is None:
        raise HTTPException(status_code=404, detail="Task not found or expired")
    return {"code": 200, "data": status}

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
