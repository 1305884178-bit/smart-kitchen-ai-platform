package com.smartkitchen.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.smartkitchen.dto.AddDishDTO;
import com.smartkitchen.dto.OrderDetailVO;
import com.smartkitchen.dto.OrderSubmitDTO;
import com.smartkitchen.dto.OrderVO;
import com.smartkitchen.entity.Order;

import java.util.List;

public interface OrderService extends IService<Order> {

    /**
     * 提交订单
     * @param submitDTO 提交参数
     * @return 订单号
     */
    String submitOrder(OrderSubmitDTO submitDTO);

    /**
     * 确认支付（先付后做；支付不等于结束用餐）
     * @param orderId 订单ID
     */
    void payOrder(Long orderId);

    /**
     * 撤销订单
     * @param orderId 订单ID
     */
    void cancelOrder(Long orderId);

    /**
     * 支付超时自动取消（MQ 延迟消息 / 扫表兜底共用）。
     * 仅当订单 ORDERED 且未支付时取消并返还库存，取消原因 PAY_TIMEOUT；
     * 父单超时可级联未支付子单；子单超时只取消自己；
     * 与支付并发时 SQL 条件更新影响 0 行，视为支付胜出，静默跳过不抛异常。
     * @param orderId 订单ID
     */
    void cancelOrderForTimeout(Long orderId);

    /**
     * 获取所有已支付待出餐（ORDERED 且 pay_time 非空）的订单及其明细，厨房看板只展示已支付单
     * @return 订单视图对象列表
     */
    List<OrderVO> getOrderedOrders();

    /**
     * 厨房完成出餐操作
     * @param orderId 订单ID
     */
    void serveOrder(Long orderId);

    /**
     * 加菜：创建新的子订单（相同座位号、独立厨房看板卡片），扣库存
     * @param orderId 原订单ID（父订单）
     * @param addDishDTO 加菜菜品列表
     */
    void addDish(Long orderId, AddDishDTO addDishDTO);

    /**
     * 顾客确认结束用餐。订单组内所有未取消订单均已付款、已出餐后，关闭为 PAID。
     * @param orderId 原订单ID（父订单）
     */
    void finishMeal(Long orderId);

    /**
     * 顾客历史订单分页查询
     * @param page 页码
     * @param size 每页大小
     * @return 分页订单列表
     */
    Page<OrderVO> listUserOrders(Integer page, Integer size);

    /**
     * 顾客订单详情（含availableActions）
     * @param orderId 订单ID
     * @return 订单详情
     */
    OrderDetailVO getUserOrderDetail(Long orderId);

    /**
     * 管理端订单详情（不限userId，含availableActions）
     * @param orderId 订单ID
     * @return 订单详情
     */
    OrderDetailVO getAdminOrderDetail(Long orderId);

    /**
     * 管理端全量订单分页查询
     * @param page 页码
     * @param size 每页大小
     * @param status 订单状态（可选）
     * @param seatNumber 座位号（可选）
     * @return 分页订单列表
     */
    Page<OrderVO> listAdminOrders(Integer page, Integer size, Integer status, String seatNumber);
}
