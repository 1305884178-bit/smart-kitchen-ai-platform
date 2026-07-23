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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class DishControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    private String token;

    @BeforeEach
    public void setup() {
        token = jwtUtil.generateToken(1001L, "oX123456789", "CUSTOMER");
    }

    @Test
    public void testGetDishList() throws Exception {
        // 请求 /api/dish/list 接口
        String responseContent = mockMvc.perform(get("/api/dish/list")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Result<List<Dish>> result = objectMapper.readValue(responseContent, new TypeReference<Result<List<Dish>>>() {});
        
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertTrue(result.getData().size() > 0);
    }

    @Test
    public void testGetDishListByCategoryId() throws Exception {
        // 请求带有 categoryId 的接口，假设 1L 是存在的分类
        String responseContent = mockMvc.perform(get("/api/dish/list")
                .header("Authorization", "Bearer " + token)
                .param("categoryId", "1")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Result<List<Dish>> result = objectMapper.readValue(responseContent, new TypeReference<Result<List<Dish>>>() {});
        
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertTrue(result.getData().size() > 0);
        for (Dish dish : result.getData()) {
            assertEquals(1L, dish.getCategoryId());
            assertEquals(1, dish.getStatus()); // 必须是上架的
        }
    }
}

