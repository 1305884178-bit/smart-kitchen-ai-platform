package com.smartkitchen.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * AI 预测结果落库请求 DTO（Python predict_agent 经内部接口上报）。
 * 字段与 Python 侧 snake_case 请求体一一对应。
 */
@Data
public class PredictUpsertDTO {
    /** 预测目标日期，格式 yyyy-MM-dd */
    @JsonProperty("predict_date")
    private String predictDate;
    @JsonProperty("dish_id")
    private Long dishId;
    @JsonProperty("base_quantity")
    private Integer baseQuantity;
    @JsonProperty("ai_suggest_quantity")
    private Integer aiSuggestQuantity;
    @JsonProperty("final_quantity")
    private Integer finalQuantity;
    private String reasoning;
    private BigDecimal confidence;
    @JsonProperty("recent_avg_score")
    private BigDecimal recentAvgScore;
}
