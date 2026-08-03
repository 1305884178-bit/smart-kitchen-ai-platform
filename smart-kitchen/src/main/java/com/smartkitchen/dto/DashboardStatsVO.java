package com.smartkitchen.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 仪表盘统计数据视图对象
 */
@Data
public class DashboardStatsVO {
    /** 今日订单数 */
    private Long todayOrderCount;
    /** 今日营收 */
    private BigDecimal todayRevenue;
    /** 待出餐数量 */
    private Long pendingServeCount;
    /** 库存预警菜品数（dailyStock <= alertThreshold） */
    private Long lowStockDishCount;
}
