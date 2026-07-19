package com.smartkitchen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartkitchen.dto.CategoryDTO;
import com.smartkitchen.entity.Category;

import java.util.List;

/**
 * 菜品分类表 Service 接口
 */
public interface CategoryService extends IService<Category> {
    List<Category> listCategories();
    boolean addCategory(CategoryDTO categoryDTO);
    boolean updateCategory(CategoryDTO categoryDTO);
}
