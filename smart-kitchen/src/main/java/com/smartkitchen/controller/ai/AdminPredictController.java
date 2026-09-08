package com.smartkitchen.controller.ai;

import com.smartkitchen.common.Result;
import com.smartkitchen.dto.PredictConfirmDTO;
import com.smartkitchen.dto.PredictTriggerDTO;
import com.smartkitchen.service.PredictionService;
import com.smartkitchen.service.PythonAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * B端备菜预测控制器。
 * trigger/status 仍代理 Python（任务进度在 Python 侧 Redis）；
 * result/confirm 已由 Java 直查/直写 MySQL（ai_prediction_record 写入口归 Java）。
 */
@RestController
@RequestMapping("/api/admin/predict")
public class AdminPredictController {

    @Autowired
    private PythonAIService pythonAIService;

    @Autowired
    private PredictionService predictionService;

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
     * 查询备菜预测结果（Java 直查 ai_prediction_record join 菜名，不再代理 Python）
     * @param targetDate 预测日期
     * @return 预测结果列表
     */
    @GetMapping("/result")
    public Result<Object> getPredictionResult(@RequestParam String targetDate) {
        try {
            return Result.success(predictionService.getPredictionResult(targetDate));
        } catch (Exception e) {
            return Result.error(500, "查询预测结果失败：" + e.getMessage());
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
     * 确认/覆盖预测量（Java 直写 MySQL；confirmedBy 取当前登录 ADMIN，忽略前端传入）
     * @param dto 包含recordId、finalQuantity
     * @return 确认结果
     */
    @PostMapping("/confirm")
    public Result<Object> confirmPrediction(@RequestBody PredictConfirmDTO dto) {
        try {
            predictionService.confirmPrediction(dto);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "确认预测失败：" + e.getMessage());
        }
    }
}
