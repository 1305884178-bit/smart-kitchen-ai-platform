package com.smartkitchen.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.common.Result;
import com.smartkitchen.config.JwtUtil;
import com.smartkitchen.entity.Dish;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AdminDishControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    private String token;

    @BeforeEach
    public void setup() {
        token = jwtUtil.generateToken(1L, "ADMIN", "admin");
    }

    @Test
    public void testCreateAndUpdateDish() throws Exception {
        Dish dish = new Dish();
        dish.setName("Test Admin Dish");
        dish.setPrice(new BigDecimal("10.5"));
        dish.setDailyStock(100);
        dish.setCategoryId(1L);

        // Create
        String createResponse = mockMvc.perform(post("/api/admin/dish/create")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dish)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Result<Object> createResult = objectMapper.readValue(createResponse, new TypeReference<Result<Object>>() {});
        assertEquals(200, createResult.getCode());
    }
}
