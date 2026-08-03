package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartkitchen.common.OrderStatusEnum;
import com.smartkitchen.dto.DashboardStatsVO;
import com.smartkitchen.entity.Dish;
import com.smartkitchen.entity.Order;
import com.smartkitchen.service.DashboardService;
import com.smartkitchen.service.DishService;
import com.smartkitchen.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 仪表盘统计服务实现类
 */
@Service
public class DashboardServiceImpl implements DashboardService {

    @Autowired
    private OrderService orderService;

    @Autowired
    private DishService dishService;

    @Override
    public DashboardStatsVO getStats() {
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);

        DashboardStatsVO vo = new DashboardStatsVO();

        // 今日订单数
        vo.setTodayOrderCount(orderService.lambdaQuery()
                .ge(Order::getCreateTime, todayStart)
                .le(Order::getCreateTime, todayEnd)
                .count());

        // 今日营收（已结账订单）
        LambdaQueryWrapper<Order> paidWrapper = new LambdaQueryWrapper<>();
        paidWrapper.eq(Order::getStatus, OrderStatusEnum.PAID.getCode())
                   .ge(Order::getPayTime, todayStart)
                   .le(Order::getPayTime, todayEnd);
        BigDecimal todayRevenue = orderService.list(paidWrapper).stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        vo.setTodayRevenue(todayRevenue);

        // 待出餐数量
        vo.setPendingServeCount(orderService.lambdaQuery()
                .eq(Order::getStatus, OrderStatusEnum.ORDERED.getCode())
                .count());

        // 库存预警菜品数（dailyStock <= alertThreshold 且 alertThreshold > 0）
        vo.setLowStockDishCount(dishService.lambdaQuery()
                .gt(Dish::getAlertThreshold, 0)
                .apply("daily_stock <= alert_threshold")
                .count());

        return vo;
    }
}
