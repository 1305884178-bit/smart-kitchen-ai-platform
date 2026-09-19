package com.smartkitchen.dto;

import lombok.Data;

/**
 * AI 客服批量查询菜品实时信息的响应对象。
 * 一次返回库存、配料和过敏原，避免多菜查询产生多次工具调用。
 */
@Data
public class DishRealtimeInfoVO {
    /** 顾客提问中的菜名 */
    private String queryName;
    /** 实际命中的标准菜名 */
    private String name;
    private Integer dailyStock;
    private Integer status;
    private String ingredients;
    private String allergens;
    private boolean found;
}
