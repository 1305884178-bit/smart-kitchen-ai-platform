package com.smartkitchen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartkitchen.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 订单主表 Mapper 接口
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 查询所有被占用的座位号（状态为 0:ORDERED 或 10:SERVED 的订单）
     *
     * @return 被占用的座位号列表
     */
    @Select("SELECT seat_number FROM oms_order WHERE status IN (0, 10)")
    List<String> selectOccupiedSeats();
}
