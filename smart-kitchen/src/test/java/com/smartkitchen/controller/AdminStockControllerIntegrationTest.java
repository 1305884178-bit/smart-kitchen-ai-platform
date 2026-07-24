package com.smartkitchen.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.common.Result;
import com.smartkitchen.config.JwtUtil;
import com.smartkitchen.entity.Dish;
import com.smartkitchen.entity.StockLog;
import com.smartkitchen.service.DishService;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public class AdminStockControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private DishService dishService;

    private String token;
    private Long testDishId;

    @BeforeEach
    public void setup() {
        token = jwtUtil.generateToken(1L, "admin", "ADMIN");

        Dish dish = new Dish();
        dish.setName("Stock Test Dish");
        dish.setPrice(new BigDecimal("10.0"));
        dish.setDailyStock(50);
        dish.setCategoryId(1L);
        dishService.save(dish);
        testDishId = dish.getId();
    }

    @Test
    public void testUpdateAndViewStock() throws Exception {
        // 1. Update stock
        String updateResponse = mockMvc.perform(put("/api/admin/stock/update/" + testDishId)
                .header("Authorization", "Bearer " + token)
                .param("changeQty", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Result<Object> updateResult = objectMapper.readValue(updateResponse, new TypeReference<Result<Object>>() {});
        assertEquals(200, updateResult.getCode());

        // 2. View stock logs
        String viewResponse = mockMvc.perform(get("/api/admin/stock/view/" + testDishId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Result<List<StockLog>> viewResult = objectMapper.readValue(viewResponse, new TypeReference<Result<List<StockLog>>>() {});
        assertEquals(200, viewResult.getCode());
        assertNotNull(viewResult.getData());
        assertEquals(1, viewResult.getData().size());
        assertEquals(10, viewResult.getData().get(0).getChangeQty());
    }
}
