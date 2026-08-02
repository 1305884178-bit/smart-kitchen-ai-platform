package com.smartkitchen.dto;

import lombok.Data;

/**
 * 菜品库存查询响应VO，供Python代理接口使用
 */
@Data
public class DishInventoryVO {
    private String name;
    private Integer dailyStock;
    private Integer status;
}
