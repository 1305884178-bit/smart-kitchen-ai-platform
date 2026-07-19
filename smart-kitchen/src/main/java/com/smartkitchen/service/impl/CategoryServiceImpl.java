package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartkitchen.dto.CategoryDTO;
import com.smartkitchen.entity.Category;
import com.smartkitchen.mapper.CategoryMapper;
import com.smartkitchen.service.CategoryService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜品分类表 Service 实现类
 */
@Service
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {

    @Override
    public List<Category> listCategories() {
        QueryWrapper<Category> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByAsc("sort");
        return this.list(queryWrapper);
    }

    @Override
    public boolean addCategory(CategoryDTO categoryDTO) {
        Category category = new Category();
        category.setName(categoryDTO.getName());
        category.setSort(categoryDTO.getSort());
        category.setCreateTime(LocalDateTime.now());
        return this.save(category);
    }

    @Override
    public boolean updateCategory(CategoryDTO categoryDTO) {
        Category category = new Category();
        category.setId(categoryDTO.getId());
        category.setName(categoryDTO.getName());
        category.setSort(categoryDTO.getSort());
        category.setUpdateTime(LocalDateTime.now());
        return this.updateById(category);
    }
}
