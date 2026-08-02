package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartkitchen.common.DishStatusEnum;
import com.smartkitchen.dto.DishIngredientVO;
import com.smartkitchen.dto.DishInventoryVO;
import com.smartkitchen.entity.Dish;
import com.smartkitchen.mapper.DishMapper;
import com.smartkitchen.service.DishService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 菜品服务实现类
 */
@Service
public class DishServiceImpl extends ServiceImpl<DishMapper, Dish> implements DishService {

    /**
     * 根据分类ID查询起售状态的菜品列表
     * @param categoryId 分类ID
     * @return 菜品列表
     */
    @Override
    public List<Dish> listByCategoryId(Long categoryId) {
        LambdaQueryWrapper<Dish> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(categoryId != null, Dish::getCategoryId, categoryId);
        queryWrapper.eq(Dish::getStatus, DishStatusEnum.ON_SALE.getCode());
        queryWrapper.orderByDesc(Dish::getUpdateTime);
        return this.list(queryWrapper);
    }

    /**
     * 根据菜品名称模糊查询菜品库存信息
     * @param dishName 菜品名称
     * @return 库存VO，未找到返回null
     */
    @Override
    public DishInventoryVO getInventoryByName(String dishName) {
        Dish dish = queryByName(dishName);
        if (dish == null) {
            return null;
        }
        DishInventoryVO vo = new DishInventoryVO();
        vo.setName(dish.getName());
        vo.setDailyStock(dish.getDailyStock());
        vo.setStatus(dish.getStatus());
        return vo;
    }

    /**
     * 根据菜品名称模糊查询菜品配料信息
     * @param dishName 菜品名称
     * @return 配料VO，未找到返回null
     */
    @Override
    public DishIngredientVO getIngredientsByName(String dishName) {
        Dish dish = queryByName(dishName);
        if (dish == null) {
            return null;
        }
        DishIngredientVO vo = new DishIngredientVO();
        vo.setName(dish.getName());
        vo.setIngredients(dish.getIngredients());
        return vo;
    }

    /**
     * 根据菜品名称模糊查询菜品实体
     * @param dishName 菜品名称
     * @return 菜品实体，未找到返回null
     */
    private Dish queryByName(String dishName) {
        LambdaQueryWrapper<Dish> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(Dish::getName, dishName).last("LIMIT 1");
        return this.getOne(queryWrapper);
    }
}
