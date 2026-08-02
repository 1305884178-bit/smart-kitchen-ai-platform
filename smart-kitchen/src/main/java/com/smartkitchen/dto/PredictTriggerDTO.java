package com.smartkitchen.dto;

import lombok.Data;

/**
 * B端备菜预测触发请求DTO
 */
@Data
public class PredictTriggerDTO {
    private String targetDate;
    private Long dishId;
}
