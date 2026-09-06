package com.smartkitchen.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.common.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 菜品代理控制器集成测试（供Python AI服务调用）。
 * 库存口径与下单一致：优先 Redis dish:stock:{id}，miss 回源 MySQL 并写入，Redis 异常降级 MySQL。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class DishProxyControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setup() {
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    public void testGetInventorySuccess() throws Exception {
        String response = mockMvc.perform(get("/api/proxy/dish/inventory")
                        .param("dishName", "水煮鱼"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertEquals("水煮鱼", result.getData().get("name"));
        assertEquals(50, result.getData().get("dailyStock"));
        assertEquals(1, result.getData().get("status"));
    }

    @Test
    public void testGetInventoryRedisHit() throws Exception {
        // Redis 命中：返回可下单剩余（与 deduct_stock.lua 同一 key）
        when(valueOperations.get("dish:stock:1")).thenReturn("42");

        String response = mockMvc.perform(get("/api/proxy/dish/inventory")
                        .param("dishName", "水煮鱼"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        assertEquals(200, result.getCode());
        assertEquals(42, result.getData().get("dailyStock"));
        // 命中时不回源写入
        verify(valueOperations, never()).set(anyString(), anyString());
    }

    @Test
    public void testGetInventoryRedisMissBackfillsFromMysql() throws Exception {
        // Redis miss：用 MySQL daily_stock 回源并写入
        when(valueOperations.get("dish:stock:1")).thenReturn(null);

        String response = mockMvc.perform(get("/api/proxy/dish/inventory")
                        .param("dishName", "水煮鱼"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        assertEquals(200, result.getCode());
        assertEquals(50, result.getData().get("dailyStock"));
        verify(valueOperations).set("dish:stock:1", "50");
    }

    @Test
    public void testGetInventoryRedisDownFallsBackToMysql() throws Exception {
        // Redis 异常：降级返回 MySQL daily_stock，不影响查询
        when(valueOperations.get("dish:stock:1")).thenThrow(new RuntimeException("connection refused"));

        String response = mockMvc.perform(get("/api/proxy/dish/inventory")
                        .param("dishName", "水煮鱼"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        assertEquals(200, result.getCode());
        assertEquals(50, result.getData().get("dailyStock"));
    }

    @Test
    public void testGetInventoryFuzzyMatch() throws Exception {
        String response = mockMvc.perform(get("/api/proxy/dish/inventory")
                        .param("dishName", "鸡丁"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        assertEquals(200, result.getCode());
        assertEquals("宫保鸡丁", result.getData().get("name"));
    }

    @Test
    public void testGetInventoryNotFound() throws Exception {
        String response = mockMvc.perform(get("/api/proxy/dish/inventory")
                        .param("dishName", "不存在的菜品"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        assertEquals(404, result.getCode());
        assertNull(result.getData());
    }

    @Test
    public void testGetIngredientsSuccess() throws Exception {
        String response = mockMvc.perform(get("/api/proxy/dish/ingredients")
                        .param("dishName", "水煮鱼"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        assertEquals(200, result.getCode());
        assertEquals("水煮鱼", result.getData().get("name"));
        assertTrue(result.getData().get("ingredients").toString().contains("草鱼"));
        assertTrue(result.getData().get("allergens").toString().contains("鱼"));
    }

    @Test
    public void testGetIngredientsNotFound() throws Exception {
        String response = mockMvc.perform(get("/api/proxy/dish/ingredients")
                        .param("dishName", "不存在的菜品"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        assertEquals(404, result.getCode());
    }
}
