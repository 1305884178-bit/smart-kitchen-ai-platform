package com.smartkitchen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartkitchen.entity.Dish;
import org.apache.ibatis.annotations.Mapper;

/**
 * 菜品表 Mapper 接口
 */
@Mapper
public interface DishMapper extends BaseMapper<Dish> {
}
