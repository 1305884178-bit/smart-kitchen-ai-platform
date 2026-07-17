package com.smartkitchen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartkitchen.entity.Category;
import org.apache.ibatis.annotations.Mapper;

/**
 * 菜品分类表 Mapper 接口
 */
@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
