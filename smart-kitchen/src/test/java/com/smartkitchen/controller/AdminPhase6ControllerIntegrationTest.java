package com.smartkitchen.controller;

import com.smartkitchen.config.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6 Step 0 管理端仪表盘、评价筛选、库存增强、STS凭证接口集成测试
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AdminPhase6ControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    private String adminToken;

    @BeforeEach
    public void setup() {
        adminToken = jwtUtil.generateToken(1000L, "ADMIN", "admin_openid");
    }

    // ==================== /api/admin/dashboard/stats ====================

    @Test
    public void testDashboardStats_shouldReturnStatistics() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard/stats")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.todayOrderCount").exists())
                .andExpect(jsonPath("$.data.todayRevenue").exists())
                .andExpect(jsonPath("$.data.pendingServeCount").exists())
                .andExpect(jsonPath("$.data.lowStockDishCount").exists());
    }

    // ==================== /api/admin/review/list ====================

    @Test
    public void testReviewList_shouldReturnAllReviews() throws Exception {
        mockMvc.perform(get("/api/admin/review/list")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    public void testReviewList_shouldFilterByScore() throws Exception {
        mockMvc.perform(get("/api/admin/review/list")
                .header("Authorization", "Bearer " + adminToken)
                .param("score", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== /api/admin/stock/view/{dishId} ====================

    @Test
    public void testStockView_shouldReturnEnhancedStockInfo() throws Exception {
        mockMvc.perform(get("/api/admin/stock/view/1")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.dishId").value(1))
                .andExpect(jsonPath("$.data.dishName").value("水煮鱼"))
                .andExpect(jsonPath("$.data.dailyStock").value(50))
                .andExpect(jsonPath("$.data.alertThreshold").value(10))
                .andExpect(jsonPath("$.data.logs").isArray());
    }

    @Test
    public void testStockView_shouldReturnEmptyForNonExistentDish() throws Exception {
        mockMvc.perform(get("/api/admin/stock/view/99999")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.dishName").isEmpty());
    }

    // ==================== /api/admin/upload/sts-token ====================

    @Test
    public void testStsToken_shouldReturnUnconfiguredStatus() throws Exception {
        mockMvc.perform(get("/api/admin/upload/sts-token")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("unconfigured"))
                .andExpect(jsonPath("$.data.region").value("cn-hangzhou"))
                .andExpect(jsonPath("$.data.bucket").isEmpty());
    }
}
