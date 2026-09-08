package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.dto.PredictUpsertDTO;
import com.smartkitchen.service.PredictionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 预测结果内部上报控制器，供 Python AI 服务调用。
 * 位于 /api/proxy/** 下：不走用户 JWT，由 InternalTokenInterceptor 校验内部 token。
 */
@RestController
@RequestMapping("/api/proxy/predict")
public class PredictProxyController {

    @Autowired
    private PredictionService predictionService;

    /**
     * 预测结果落库（按 predict_date + dish_id 有则更新建议字段，无则插入）
     * @param dto 预测结果字段
     * @return 处理结果
     */
    @PostMapping("/upsert")
    public Result<Object> upsert(@RequestBody PredictUpsertDTO dto) {
        try {
            predictionService.upsertPrediction(dto);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "预测结果落库失败：" + e.getMessage());
        }
    }
}
