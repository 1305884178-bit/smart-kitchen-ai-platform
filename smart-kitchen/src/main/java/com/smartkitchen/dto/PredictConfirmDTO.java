package com.smartkitchen.dto;

import lombok.Data;

/**
 * B端备菜预测确认请求DTO
 */
@Data
public class PredictConfirmDTO {
    private Long recordId;
    private Integer finalQuantity;
    private Long confirmedBy;
}
