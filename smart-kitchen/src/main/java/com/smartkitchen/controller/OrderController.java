package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import org.springframework.web.bind.annotation.*;

/**
 * 订单业务控制器
 */
@RestController
@RequestMapping("/api/order")
public class OrderController {

    /**
     * 提交订单（包含 Redis Lua 预扣库存逻辑）
     * @return 返回订单创建结果
     */
    @PostMapping("/submit")
    public Result<Object> submitOrder() {
        return Result.success(null);
    }

    /**
     * 订单加菜（追加明细、扣库存、若当前状态为SERVED则回退到ORDERED）
     * @param id 订单ID
     * @return 返回加菜结果
     */
    @PostMapping("/{id}/add-dish")
    public Result<Object> addDish(@PathVariable("id") Long id) {
        return Result.success(null);
    }

    /**
     * 结账支付（将订单状态变更为PAID，并通知厨房看板）
     * @param id 订单ID
     * @return 返回支付结果
     */
    @PostMapping("/{id}/pay")
    public Result<Object> pay(@PathVariable("id") Long id) {
        return Result.success(null);
    }

    /**
     * 顾客查询自己的订单列表
     * @return 返回顾客的历史订单列表
     */
    @GetMapping("/my-list")
    public Result<Object> myList() {
        return Result.success(null);
    }

    /**
     * 顾客查询订单详情（包含页面按钮可用状态等信息）
     * @param id 订单ID
     * @return 返回订单详细信息
     */
    @GetMapping("/my-detail/{id}")
    public Result<Object> myDetail(@PathVariable("id") Long id) {
        return Result.success(null);
    }

    /**
     * 管理端获取所有订单列表
     * @return 返回所有订单数据（可带筛选条件）
     */
    @GetMapping("/admin-list")
    public Result<Object> adminList() {
        return Result.success(null);
    }

    /**
     * 管理员撤销订单（支持ORDERED或SERVED状态变更为CANCELLED）
     * @param id 订单ID
     * @return 返回撤销操作结果
     */
    @PostMapping("/{id}/cancel")
    public Result<Object> cancel(@PathVariable("id") Long id) {
        return Result.success(null);
    }

    /**
     * 厨房看板确认完成（将订单状态从ORDERED变更为SERVED）
     * @param id 订单ID
     * @return 返回完成操作结果
     */
    @PostMapping("/{id}/complete")
    public Result<Object> complete(@PathVariable("id") Long id) {
        return Result.success(null);
    }
}
