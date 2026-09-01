package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 厨房看板控制器
 */
@RestController
@RequestMapping("/api/kitchen-board")
public class KitchenBoardController {

    @Autowired
    private OrderService orderService;

    /**
     * 获取当前已支付待出餐（ORDERED 且 pay_time 非空）的订单 HTTP 快照（主要用于 WebSocket 断线重连时的数据补齐）
     * @return 返回待制作订单列表
     */
    @GetMapping("/orders")
    public Result<Object> getOrdersSnapshot() {
        return Result.success(orderService.getOrderedOrders());
    }

    /**
     * 厨房完成出餐操作
     * @param orderId 订单ID
     * @return 操作结果
     */
    @PostMapping("/order/{id}/serve")
    public Result<Object> serveOrder(@PathVariable("id") Long orderId) {
        orderService.serveOrder(orderId);
        return Result.success();
    }
}
