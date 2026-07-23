package com.smartkitchen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartkitchen.dto.OrderSubmitDTO;
import com.smartkitchen.entity.Order;

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
}
