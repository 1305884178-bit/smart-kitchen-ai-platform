package com.smartkitchen.dto;

import lombok.Data;

/**
 * 菜品分类数据传输对象
 */
@Data
public class CategoryDTO {
    private Long id;
    private String name;
    private Integer sort;
}
