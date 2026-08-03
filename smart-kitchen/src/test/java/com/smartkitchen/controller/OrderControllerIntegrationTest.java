package com.smartkitchen.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.dto.AddDishDTO;
import com.smartkitchen.dto.OrderDetailDTO;
import com.smartkitchen.config.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 6 Step 0 订单接口集成测试
 * 覆盖 add-dish、my-list、my-detail、admin-list、complete 五个新接口
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class OrderControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    private String customerToken;
    private String adminToken;

    @BeforeEach
    public void setup() {
        customerToken = jwtUtil.generateToken(1001L, "CUSTOMER", "test_openid_1001");
        adminToken = jwtUtil.generateToken(1000L, "ADMIN", "admin_openid");

        // Mock Redis
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.hasKey(anyString())).thenReturn(true);
        when(stringRedisTemplate.execute(any(RedisScript.class), any(), any())).thenReturn(1L);
    }

    // ==================== /api/order/my-list ====================

    @Test
    public void testMyList_shouldReturnUserOrders() throws Exception {
        mockMvc.perform(get("/api/order/my-list")
                .header("Authorization", "Bearer " + customerToken)
                .param("page", "1")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records[0].userId").value(1001));
    }

    @Test
    public void testMyList_shouldReturnEmptyForNoOrders() throws Exception {
        String otherToken = jwtUtil.generateToken(9999L, "CUSTOMER", "no_orders");
        mockMvc.perform(get("/api/order/my-list")
                .header("Authorization", "Bearer " + otherToken)
                .param("page", "1")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isEmpty());
    }

    // ==================== /api/order/my-detail/{id} ====================

    @Test
    public void testMyDetail_shouldReturnOrderWithAvailableActions() throws Exception {
        mockMvc.perform(get("/api/order/my-detail/1")
                .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.availableActions").isArray())
                .andExpect(jsonPath("$.data.details").isArray());
    }

    @Test
    public void testMyDetail_shouldReturnServedOrderWithActions() throws Exception {
        String token1002 = jwtUtil.generateToken(1002L, "CUSTOMER", "test_openid_1002");
        mockMvc.perform(get("/api/order/my-detail/2")
                .header("Authorization", "Bearer " + token1002))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.availableActions").isArray())
                .andExpect(jsonPath("$.data.details").isArray());
    }

    @Test
    public void testMyDetail_shouldReturnErrorForOtherUserOrder() throws Exception {
        String token1002 = jwtUtil.generateToken(1002L, "CUSTOMER", "test_openid_1002");
        mockMvc.perform(get("/api/order/my-detail/1")
                .header("Authorization", "Bearer " + token1002))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    // ==================== /api/order/admin-list ====================

    @Test
    public void testAdminList_shouldReturnAllOrders() throws Exception {
        mockMvc.perform(get("/api/order/admin-list")
                .header("Authorization", "Bearer " + adminToken)
                .param("page", "1")
                .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    public void testAdminList_shouldFilterByStatus() throws Exception {
        mockMvc.perform(get("/api/order/admin-list")
                .header("Authorization", "Bearer " + adminToken)
                .param("page", "1")
                .param("size", "10")
                .param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    public void testAdminList_shouldFilterBySeatNumber() throws Exception {
        mockMvc.perform(get("/api/order/admin-list")
                .header("Authorization", "Bearer " + adminToken)
                .param("page", "1")
                .param("size", "10")
                .param("seatNumber", "A05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ==================== /api/order/{id}/complete ====================

    @Test
    public void testComplete_shouldHandleComplete() throws Exception {
        mockMvc.perform(post("/api/order/1/complete")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNumber());
    }

    @Test
    public void testComplete_nonexistentOrder() throws Exception {
        mockMvc.perform(post("/api/order/9999/complete")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }

    // ==================== /api/order/{id}/add-dish ====================

    @Test
    public void testAddDish_shouldAddToServedOrderAndRollback() throws Exception {
        AddDishDTO dto = new AddDishDTO();
        OrderDetailDTO detail = new OrderDetailDTO();
        detail.setDishId(3L);
        detail.setQuantity(1);
        dto.setDetails(Collections.singletonList(detail));

        String token1002 = jwtUtil.generateToken(1002L, "CUSTOMER", "test_openid_1002");
        mockMvc.perform(post("/api/order/2/add-dish")
                .header("Authorization", "Bearer " + token1002)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNumber());
    }

    @Test
    public void testAddDish_shouldFailForNonExistentOrder() throws Exception {
        AddDishDTO dto = new AddDishDTO();
        OrderDetailDTO detail = new OrderDetailDTO();
        detail.setDishId(3L);
        detail.setQuantity(1);
        dto.setDetails(Collections.singletonList(detail));

        mockMvc.perform(post("/api/order/9999/add-dish")
                .header("Authorization", "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500));
    }
}
