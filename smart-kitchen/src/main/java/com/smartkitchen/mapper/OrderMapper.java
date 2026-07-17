package com.smartkitchen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartkitchen.entity.Order;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单主表 Mapper 接口
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {
}
