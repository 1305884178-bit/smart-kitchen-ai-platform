package com.smartkitchen.controller.ai;

import com.smartkitchen.common.Result;
import com.smartkitchen.dto.PredictConfirmDTO;
import com.smartkitchen.dto.PredictTriggerDTO;
import com.smartkitchen.service.PythonAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * B端备菜预测代理控制器
 */
@RestController
@RequestMapping("/api/admin/predict")
public class AdminPredictController {

    @Autowired
    private PythonAIService pythonAIService;

    /**
     * 触发备菜预测
     * @param dto 可选的targetDate和dishId
     * @return 预测触发结果
     */
    @PostMapping("/trigger")
    public Result<Object> triggerPrediction(@RequestBody PredictTriggerDTO dto) {
        try {
            return Result.success(pythonAIService.triggerPrediction(dto));
        } catch (Exception e) {
            return Result.error(500, "Python AI服务调用失败：" + e.getMessage());
        }
    }

    /**
     * 查询备菜预测结果
     * @param targetDate 预测日期
     * @return 预测结果列表
     */
    @GetMapping("/result")
    public Result<Object> getPredictionResult(@RequestParam String targetDate) {
        try {
            return Result.success(pythonAIService.getPredictionResult(targetDate));
        } catch (Exception e) {
            return Result.error(500, "Python AI服务调用失败：" + e.getMessage());
        }
    }

    /**
     * 查询预测任务状态
     * @param taskId 任务ID
     * @return 任务状态
     */
    @GetMapping("/status")
    public Result<Object> getPredictionStatus(@RequestParam String taskId) {
        try {
            return Result.success(pythonAIService.getPredictionStatus(taskId));
        } catch (Exception e) {
            return Result.error(500, "Python AI服务调用失败：" + e.getMessage());
        }
    }

    /**
     * 确认/覆盖预测量
     * @param dto 包含recordId、finalQuantity、confirmedBy
     * @return 确认结果
     */
    @PostMapping("/confirm")
    public Result<Object> confirmPrediction(@RequestBody PredictConfirmDTO dto) {
        try {
            return Result.success(pythonAIService.confirmPrediction(dto));
        } catch (Exception e) {
            return Result.error(500, "Python AI服务调用失败：" + e.getMessage());
        }
    }
}
