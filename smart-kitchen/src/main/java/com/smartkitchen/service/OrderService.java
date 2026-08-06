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

    /**
     * 加菜：扣库存 + 追加明细 + SERVED→ORDERED状态回退
     * @param orderId 订单ID
     * @param addDishDTO 加菜菜品列表
     */
    void addDish(Long orderId, AddDishDTO addDishDTO);

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
     * 管理端全量订单分页查询
     * @param page 页码
     * @param size 每页大小
     * @param status 订单状态（可选）
     * @param seatNumber 座位号（可选）
     * @return 分页订单列表
     */
    Page<OrderVO> listAdminOrders(Integer page, Integer size, Integer status, String seatNumber);
}
