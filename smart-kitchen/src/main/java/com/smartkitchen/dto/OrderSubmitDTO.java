package com.smartkitchen.dto;

import lombok.Data;

import java.util.List;

/**
 * 提交订单参数
 */
@Data
public class OrderSubmitDTO {
    private String seatNumber;
    private String remark;
    private List<OrderDetailDTO> details;
}
