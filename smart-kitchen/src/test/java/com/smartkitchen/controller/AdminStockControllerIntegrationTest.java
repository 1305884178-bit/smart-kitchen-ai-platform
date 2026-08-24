package com.smartkitchen.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.common.Result;
import com.smartkitchen.config.JwtUtil;
import com.smartkitchen.dto.StockVO;
import com.smartkitchen.entity.Dish;
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
        token = jwtUtil.generateToken(1L, "ADMIN", "admin");

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

        // 2. View stock logs (now returns StockVO instead of List<StockLog>)
        String viewResponse = mockMvc.perform(get("/api/admin/stock/view/" + testDishId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Result<StockVO> viewResult = objectMapper.readValue(viewResponse, new TypeReference<Result<StockVO>>() {});
        assertEquals(200, viewResult.getCode());
        assertNotNull(viewResult.getData());
        assertEquals(testDishId, viewResult.getData().getDishId());
        assertEquals("Stock Test Dish", viewResult.getData().getDishName());
        assertEquals(60, viewResult.getData().getDailyStock());
        assertNotNull(viewResult.getData().getLogs());
        assertEquals(1, viewResult.getData().getLogs().size());
        assertEquals(10, viewResult.getData().getLogs().get(0).getChangeQty());
    }
}
