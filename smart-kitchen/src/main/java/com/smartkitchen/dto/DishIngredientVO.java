package com.smartkitchen.dto;

import lombok.Data;

/**
 * 菜品配料查询响应VO，供Python代理接口使用
 */
@Data
public class DishIngredientVO {
    private String name;
    private String ingredients;
    private String allergens;
}
