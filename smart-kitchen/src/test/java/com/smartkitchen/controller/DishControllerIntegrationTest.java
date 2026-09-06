package com.smartkitchen.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.common.Result;
import com.smartkitchen.config.JwtUtil;
import com.smartkitchen.dto.DishDetailVO;
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
        token = jwtUtil.generateToken(1001L, "CUSTOMER", "oX123456789");
    }

    @Test
    public void testGetDishList() throws Exception {
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
            assertEquals(1, dish.getStatus());
        }
    }

    @Test
    public void testGetDishDetail() throws Exception {
        String responseContent = mockMvc.perform(get("/api/dish/detail/1")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Result<DishDetailVO> result = objectMapper.readValue(responseContent, new TypeReference<Result<DishDetailVO>>() {});
        
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertEquals(1L, result.getData().getId().longValue());
        assertNotNull(result.getData().getName());
        assertNotNull(result.getData().getCategoryName());
        assertNotNull(result.getData().getPrice());
        assertNotNull(result.getData().getIngredients());
        assertNotNull(result.getData().getReviews());
    }

    @Test
    public void testGetDishDetailNotFound() throws Exception {
        String responseContent = mockMvc.perform(get("/api/dish/detail/9999")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Result<DishDetailVO> result = objectMapper.readValue(responseContent, new TypeReference<Result<DishDetailVO>>() {});
        
        assertEquals(404, result.getCode());
        assertNull(result.getData());
    }

    @Test
    public void testGetCategoryList_customerToken_shouldSucceed() throws Exception {
        String responseContent = mockMvc.perform(get("/api/dish/category/list")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Result<List<com.smartkitchen.entity.Category>> result = objectMapper.readValue(
                responseContent, new TypeReference<Result<List<com.smartkitchen.entity.Category>>>() {});
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertFalse(result.getData().isEmpty());
    }
}
