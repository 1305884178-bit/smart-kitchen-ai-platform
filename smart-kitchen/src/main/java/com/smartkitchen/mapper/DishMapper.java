package com.smartkitchen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartkitchen.entity.Dish;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 菜品表 Mapper 接口
 */
@Mapper
public interface DishMapper extends BaseMapper<Dish> {
    
    /**
     * 乐观锁扣减库存
     * @param id 菜品ID
     * @param quantity 扣减数量
     * @return 影响行数
     */
    int deductStock(@Param("id") Long id, @Param("quantity") Integer quantity);
}
