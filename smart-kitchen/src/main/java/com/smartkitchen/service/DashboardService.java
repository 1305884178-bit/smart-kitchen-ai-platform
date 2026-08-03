package com.smartkitchen.service;

import com.smartkitchen.dto.DashboardStatsVO;

/**
 * 仪表盘统计服务接口
 */
public interface DashboardService {

    /**
     * 获取仪表盘统计数据（今日订单数、今日营收、待出餐数量、库存预警菜品数）
     * @return 统计数据
     */
    DashboardStatsVO getStats();
}
