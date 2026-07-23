package com.smartkitchen.dto;

import lombok.Data;

/**
 * 订单明细参数
 */
@Data
public class OrderDetailDTO {
    private Long dishId;
    private Integer quantity;
}
