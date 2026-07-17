package com.smartkitchen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartkitchen.entity.OrderDetail;
import org.apache.ibatis.annotations.Mapper;

/**
 * 订单明细表 Mapper 接口
 */
@Mapper
public interface OrderDetailMapper extends BaseMapper<OrderDetail> {
}
