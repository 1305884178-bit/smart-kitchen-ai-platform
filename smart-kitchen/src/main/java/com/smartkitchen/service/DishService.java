package com.smartkitchen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartkitchen.dto.DishDetailVO;
import com.smartkitchen.dto.DishIngredientVO;
import com.smartkitchen.dto.DishInventoryVO;
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

    /**
     * 管理端查询所有菜品（含已下架）
     * @param categoryId 分类ID，可选
     * @return 菜品列表
     */
    List<Dish> listAll(Long categoryId);

    /**
     * 根据菜品ID查询菜品详情（含分类名与已有评价）
     * @param dishId 菜品ID
     * @return 菜品详情VO，未找到返回null
     */
    DishDetailVO getDishDetail(Long dishId);

    /**
     * 根据菜品名称模糊查询菜品库存信息
     * @param dishName 菜品名称
     * @return 库存VO，未找到返回null
     */
    DishInventoryVO getInventoryByName(String dishName);

    /**
     * 根据菜品名称模糊查询菜品配料信息
     * @param dishName 菜品名称
     * @return 配料VO，未找到返回null
     */
    DishIngredientVO getIngredientsByName(String dishName);
}
