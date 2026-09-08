package com.smartkitchen.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.common.Result;
import com.smartkitchen.entity.PredictionRecord;
import com.smartkitchen.mapper.PredictionRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/proxy/** 服务间内部 token 鉴权集成测试（已配置 internal-token 的场景）。
 * - 未带/带错 token → 401；带对 token → 正常业务响应
 * - 未配置 token 时的放行行为由 DishProxyControllerIntegrationTest 覆盖（test profile 默认不配置）
 */
@SpringBootTest(properties = "smart-kitchen.python-service.internal-token=test-internal-token")
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class ProxyInternalTokenIntegrationTest {

    private static final String TOKEN = "test-internal-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PredictionRecordMapper predictionRecordMapper;

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
    public void testInventoryWithoutTokenRejected() throws Exception {
        String response = mockMvc.perform(get("/api/proxy/dish/inventory")
                        .param("dishName", "水煮鱼"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        assertEquals(401, result.getCode());
    }

    @Test
    public void testInventoryWithWrongTokenRejected() throws Exception {
        mockMvc.perform(get("/api/proxy/dish/inventory")
                        .param("dishName", "水煮鱼")
                        .header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testInventoryWithCorrectToken() throws Exception {
        // 带对内部 token → 200；库存口径不变（Redis miss 回源 MySQL 并回填）
        when(valueOperations.get("dish:stock:1")).thenReturn(null);

        String response = mockMvc.perform(get("/api/proxy/dish/inventory")
                        .param("dishName", "水煮鱼")
                        .header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        assertEquals(200, result.getCode());
        assertEquals("水煮鱼", result.getData().get("name"));
        assertEquals(50, result.getData().get("dailyStock"));
    }

    @Test
    public void testPredictUpsertWithoutTokenRejected() throws Exception {
        mockMvc.perform(post("/api/proxy/predict/upsert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody("2026-08-10", 1L, 60)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testPredictUpsertWithTokenInsertThenUpdate() throws Exception {
        LocalDate date = LocalDate.of(2026, 8, 10);

        // 首次 upsert：插入，status=0 待确认
        String response = mockMvc.perform(post("/api/proxy/predict/upsert")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody("2026-08-10", 1L, 60)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        assertEquals(200, result.getCode());

        PredictionRecord inserted = predictionRecordMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PredictionRecord>()
                        .eq("predict_date", date).eq("dish_id", 1L));
        assertNotNull(inserted);
        assertEquals(60, inserted.getAiSuggestQuantity());
        assertEquals(0, inserted.getStatus());

        // 人工确认后再次 upsert 同一日期+菜品：覆盖建议字段且 status 置回 0 待确认
        inserted.setStatus(1);
        inserted.setConfirmedBy(1L);
        predictionRecordMapper.updateById(inserted);

        mockMvc.perform(post("/api/proxy/predict/upsert")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody("2026-08-10", 1L, 77)))
                .andExpect(status().isOk());

        PredictionRecord updated = predictionRecordMapper.selectById(inserted.getId());
        assertEquals(77, updated.getAiSuggestQuantity());
        assertEquals(0, updated.getStatus());
        assertNull(updated.getConfirmedBy());
    }

    private String upsertBody(String predictDate, long dishId, int suggestQuantity) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("predict_date", predictDate);
        body.put("dish_id", dishId);
        body.put("base_quantity", suggestQuantity - 5);
        body.put("ai_suggest_quantity", suggestQuantity);
        body.put("final_quantity", suggestQuantity);
        body.put("reasoning", "test upsert");
        body.put("confidence", 0.8);
        body.put("recent_avg_score", 4.5);
        return objectMapper.writeValueAsString(body);
    }
}
