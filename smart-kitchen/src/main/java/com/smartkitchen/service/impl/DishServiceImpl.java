package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartkitchen.common.DishStatusEnum;
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
}
