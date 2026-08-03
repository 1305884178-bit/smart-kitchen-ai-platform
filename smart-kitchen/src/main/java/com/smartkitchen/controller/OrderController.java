package com.smartkitchen.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartkitchen.common.Result;
import com.smartkitchen.dto.AddDishDTO;
import com.smartkitchen.dto.OrderDetailVO;
import com.smartkitchen.dto.OrderSubmitDTO;
import com.smartkitchen.dto.OrderVO;
import com.smartkitchen.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 订单业务控制器
 */
@RestController
@RequestMapping("/api/order")
public class OrderController {

    @Autowired
    private OrderService orderService;

    /**
     * 提交订单（包含 Redis Lua 预扣库存逻辑）
     * @return 返回订单创建结果
     */
    @PostMapping("/submit")
    public Result<String> submitOrder(@RequestBody OrderSubmitDTO submitDTO, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        String orderNo = orderService.submitOrder(submitDTO, userId);
        return Result.success(orderNo);
    }

    /**
     * 订单加菜（追加明细、扣库存、若当前状态为SERVED则回退到ORDERED）
     * @param id 订单ID
     * @param addDishDTO 加菜菜品列表
     * @return 返回加菜结果
     */
    @PostMapping("/{id}/add-dish")
    public Result<Object> addDish(@PathVariable("id") Long id, @RequestBody AddDishDTO addDishDTO) {
        orderService.addDish(id, addDishDTO);
        return Result.success();
    }

    /**
     * 结账支付（将订单状态变更为PAID，并通知厨房看板）
     * @param id 订单ID
     * @return 返回支付结果
     */
    @PostMapping("/{id}/pay")
    public Result<Object> pay(@PathVariable("id") Long id) {
        orderService.payOrder(id);
        return Result.success();
    }

    /**
     * 顾客查询自己的订单列表
     * @param page 页码（默认1）
     * @param size 每页大小（默认10）
     * @return 返回顾客的历史订单列表
     */
    @GetMapping("/my-list")
    public Result<Page<OrderVO>> myList(@RequestParam(defaultValue = "1") Integer page,
                                         @RequestParam(defaultValue = "10") Integer size,
                                         HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        Page<OrderVO> result = orderService.listUserOrders(userId, page, size);
        return Result.success(result);
    }

    /**
     * 顾客查询订单详情（包含页面按钮可用状态等信息）
     * @param id 订单ID
     * @return 返回订单详细信息
     */
    @GetMapping("/my-detail/{id}")
    public Result<OrderDetailVO> myDetail(@PathVariable("id") Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        OrderDetailVO vo = orderService.getUserOrderDetail(id, userId);
        return Result.success(vo);
    }

    /**
     * 管理端获取所有订单列表
     * @param page 页码（默认1）
     * @param size 每页大小（默认10）
     * @param status 订单状态筛选（可选）
     * @param seatNumber 座位号筛选（可选）
     * @return 返回所有订单数据
     */
    @GetMapping("/admin-list")
    public Result<Page<OrderVO>> adminList(@RequestParam(defaultValue = "1") Integer page,
                                            @RequestParam(defaultValue = "10") Integer size,
                                            @RequestParam(required = false) Integer status,
                                            @RequestParam(required = false) String seatNumber) {
        Page<OrderVO> result = orderService.listAdminOrders(page, size, status, seatNumber);
        return Result.success(result);
    }

    /**
     * 管理员撤销订单（支持ORDERED或SERVED状态变更为CANCELLED）
     * @param id 订单ID
     * @return 返回撤销操作结果
     */
    @PostMapping("/{id}/cancel")
    public Result<Object> cancel(@PathVariable("id") Long id) {
        orderService.cancelOrder(id);
        return Result.success();
    }

    /**
     * 厨房完成出餐（将订单状态从ORDERED变更为SERVED）
     * @param id 订单ID
     * @return 返回完成操作结果
     */
    @PostMapping("/{id}/complete")
    public Result<Object> complete(@PathVariable("id") Long id) {
        orderService.serveOrder(id);
        return Result.success();
    }
}
