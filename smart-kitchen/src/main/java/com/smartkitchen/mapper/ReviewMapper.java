package com.smartkitchen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartkitchen.entity.Review;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评价表 Mapper 接口
 */
@Mapper
public interface ReviewMapper extends BaseMapper<Review> {
}
