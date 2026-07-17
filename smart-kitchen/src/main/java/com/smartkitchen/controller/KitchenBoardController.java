package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 厨房看板控制器
 */
@RestController
@RequestMapping("/api/kitchen-board")
public class KitchenBoardController {

    /**
     * 获取当前 ORDERED 状态的订单 HTTP 快照（主要用于 WebSocket 断线重连时的数据补齐）
     * @return 返回待制作订单列表
     */
    @GetMapping("/orders")
    public Result<Object> getOrdersSnapshot() {
        return Result.success(null);
    }
}
