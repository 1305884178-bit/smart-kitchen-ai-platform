package com.smartkitchen.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.common.OrderStatusEnum;
import com.smartkitchen.common.Result;
import com.smartkitchen.config.JwtUtil;
import com.smartkitchen.entity.Order;
import com.smartkitchen.entity.Review;
import com.smartkitchen.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class ReviewControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private OrderService orderService;

    private String customerToken;
    private String adminToken;
    private Long paidOrderId;
    private Long orderedOrderId;

    @BeforeEach
    public void setup() {
        customerToken = jwtUtil.generateToken(1001L, "oX123456789", "CUSTOMER");
        adminToken = jwtUtil.generateToken(1L, "admin", "ADMIN");

        Order paidOrder = new Order();
        paidOrder.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        paidOrder.setStatus(OrderStatusEnum.PAID.getCode());
        paidOrder.setTotalAmount(new BigDecimal("100"));
        paidOrder.setUserId(1001L);
        orderService.save(paidOrder);
        paidOrderId = paidOrder.getId();

        Order orderedOrder = new Order();
        orderedOrder.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        orderedOrder.setStatus(OrderStatusEnum.ORDERED.getCode());
        orderedOrder.setTotalAmount(new BigDecimal("50"));
        orderedOrder.setUserId(1001L);
        orderService.save(orderedOrder);
        orderedOrderId = orderedOrder.getId();
    }

    @Test
    public void testSubmitReviewSuccess() throws Exception {
        Review review = new Review();
        review.setOrderId(paidOrderId);
        review.setScore(5);
        review.setComment("Very good!");

        String response = mockMvc.perform(post("/api/review/submit")
                .header("Authorization", "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(review)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Result<Object> result = objectMapper.readValue(response, new TypeReference<Result<Object>>() {});
        assertEquals(200, result.getCode());
    }

    @Test
    public void testSubmitReviewFailNotPaid() throws Exception {
        Review review = new Review();
        review.setOrderId(orderedOrderId);
        review.setScore(5);
        review.setComment("Good!");

        String response = mockMvc.perform(post("/api/review/submit")
                .header("Authorization", "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(review)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Result<Object> result = objectMapper.readValue(response, new TypeReference<Result<Object>>() {});
        assertEquals(400, result.getCode());
    }

    @Test
    public void testAdminListReviews() throws Exception {
        // 先提交一个评价
        Review review = new Review();
        review.setOrderId(paidOrderId);
        review.setScore(5);
        review.setComment("Very good!");

        mockMvc.perform(post("/api/review/submit")
                .header("Authorization", "Bearer " + customerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(review)))
                .andExpect(status().isOk());

        // 管理员查询
        String response = mockMvc.perform(get("/api/admin/review/list")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Result<List<Review>> result = objectMapper.readValue(response, new TypeReference<Result<List<Review>>>() {});
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertTrue(result.getData().size() > 0);
    }
}
