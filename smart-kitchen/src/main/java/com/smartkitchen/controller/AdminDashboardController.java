package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.dto.DashboardStatsVO;
import com.smartkitchen.service.DashboardService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端仪表盘统计控制器
 */
@RestController
@RequestMapping("/api/admin/dashboard")
public class AdminDashboardController {

    @Autowired
    private DashboardService dashboardService;

    /**
     * 获取仪表盘统计数据（今日订单数、今日营收、待出餐数量、库存预警菜品数）
     * @return 统计数据
     */
    @GetMapping("/stats")
    public Result<DashboardStatsVO> stats() {
        return Result.success(dashboardService.getStats());
    }
}
