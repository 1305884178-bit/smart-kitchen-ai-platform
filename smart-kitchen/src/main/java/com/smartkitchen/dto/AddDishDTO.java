package com.smartkitchen.dto;

import lombok.Data;

import java.util.List;

/**
 * 加菜请求参数
 */
@Data
public class AddDishDTO {
    private List<OrderDetailDTO> details;
}
