package com.smartkitchen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartkitchen.dto.OrderSubmitDTO;
import com.smartkitchen.dto.OrderVO;
import com.smartkitchen.entity.Order;

import java.util.List;

public interface OrderService extends IService<Order> {

    /**
     * 提交订单
     * @param submitDTO 提交参数
     * @param userId 当前用户ID
     * @return 订单号
     */
    String submitOrder(OrderSubmitDTO submitDTO, Long userId);

    /**
     * 确认结账
     * @param orderId 订单ID
     */
    void payOrder(Long orderId);

    /**
     * 撤销订单
     * @param orderId 订单ID
     */
    void cancelOrder(Long orderId);
    /**
     * 获取所有状态为待出餐（ORDERED）的订单及其明细
     * @return 订单视图对象列表
     */
    List<OrderVO> getOrderedOrders();

    /**
     * 厨房完成出餐操作
     * @param orderId 订单ID
     */
    void serveOrder(Long orderId);
}
