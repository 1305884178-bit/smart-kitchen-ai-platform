package com.smartkitchen.service;

import com.smartkitchen.dto.PredictConfirmDTO;
import com.smartkitchen.dto.PredictUpsertDTO;

import java.util.List;
import java.util.Map;

/**
 * AI 备菜预测业务服务：ai_prediction_record 的唯一写入口（MySQL 业务写归 Java）。
 * Python 侧仅通过内部接口上报预测结果，不再直连 MySQL 写该表。
 */
public interface PredictionService {

    /**
     * 人工确认/覆盖预测量。confirmedBy 取当前登录管理员（UserContext），忽略前端传入值。
     * 幂等：重复确认同一值视为成功。
     * @param dto 包含 recordId、finalQuantity
     * @throws IllegalArgumentException 记录不存在时抛出
     */
    void confirmPrediction(PredictConfirmDTO dto);

    /**
     * 查询指定日期的预测结果（join 菜名，snake_case 字段与旧 Python 返回对齐）
     * @param targetDate 预测日期 yyyy-MM-dd
     * @return 预测结果列表
     */
    List<Map<String, Object>> getPredictionResult(String targetDate);

    /**
     * Python 预测工作流上报落库：按 uk_date_dish（predict_date + dish_id）存在则更新建议字段，
     * 否则插入。规则：重新预测覆盖建议字段并将 status 置回 0（待确认）、清空 confirmed_by，
     * 即已确认记录被新预测覆盖后需重新人工确认。
     * @param dto 预测结果字段
     */
    void upsertPrediction(PredictUpsertDTO dto);
}
