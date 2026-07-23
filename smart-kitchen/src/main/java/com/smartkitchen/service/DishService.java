package com.smartkitchen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartkitchen.entity.Dish;

import java.util.List;

/**
 * 菜品服务接口
 */
public interface DishService extends IService<Dish> {
    
    /**
     * 根据分类ID查询起售状态的菜品列表
     * @param categoryId 分类ID
     * @return 菜品列表
     */
    List<Dish> listByCategoryId(Long categoryId);
}
