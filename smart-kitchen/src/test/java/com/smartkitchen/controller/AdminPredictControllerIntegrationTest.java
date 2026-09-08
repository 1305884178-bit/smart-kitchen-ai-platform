package com.smartkitchen.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.common.Result;
import com.smartkitchen.config.JwtUtil;
import com.smartkitchen.dto.PredictConfirmDTO;
import com.smartkitchen.dto.PredictTriggerDTO;
import com.smartkitchen.entity.PredictionRecord;
import com.smartkitchen.mapper.PredictionRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B端备菜预测控制器集成测试。
 * trigger/status 仍代理 Python（Python 未启动时预期 500）；
 * result/confirm 已收归 Java 直查/直写 MySQL（H2）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AdminPredictControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private PredictionRecordMapper predictionRecordMapper;

    /** 与 JWT 中 userId 一致的管理员 id */
    private static final long ADMIN_ID = 1L;

    private String token;

    @BeforeEach
    public void setup() {
        token = jwtUtil.generateToken(ADMIN_ID, "ADMIN", "admin");
    }

    private PredictionRecord insertRecord(LocalDate predictDate, long dishId) {
        PredictionRecord record = new PredictionRecord();
        record.setPredictDate(predictDate);
        record.setDishId(dishId);
        record.setBaseQuantity(50);
        record.setAiSuggestQuantity(60);
        record.setFinalQuantity(60);
        record.setReasoning("时序+LLM");
        record.setConfidence(new BigDecimal("0.80"));
        record.setRecentAvgScore(new BigDecimal("4.5"));
        record.setStatus(0);
        predictionRecordMapper.insert(record);
        return record;
    }

    @Test
    public void testTriggerPredictionUnauthorized() throws Exception {
        PredictTriggerDTO dto = new PredictTriggerDTO();
        dto.setTargetDate("2026-08-03");

        mockMvc.perform(post("/api/admin/predict/trigger")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testTriggerPredictionWithToken() throws Exception {
        PredictTriggerDTO dto = new PredictTriggerDTO();
        dto.setTargetDate("2026-08-03");

        String response = mockMvc.perform(post("/api/admin/predict/trigger")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        // Python服务未启动时预期返回500错误
        if (result.getCode() == 200) {
            assertNotNull(result.getData());
        } else {
            assertEquals(500, result.getCode());
            assertTrue(result.getMessage().contains("Python AI服务调用失败"));
        }
    }

    @Test
    public void testGetPredictionResultUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/predict/result")
                        .param("targetDate", "2026-08-03"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testGetPredictionResultWithToken() throws Exception {
        // Java 直查 MySQL：插入一条记录（dish_id=1 为 data-h2 的水煮鱼），验证 snake_case 字段对齐 admin-web
        LocalDate date = LocalDate.of(2026, 8, 3);
        insertRecord(date, 1L);

        String response = mockMvc.perform(get("/api/admin/predict/result")
                        .header("Authorization", "Bearer " + token)
                        .param("targetDate", "2026-08-03"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<List<Map<String, Object>>> result = objectMapper.readValue(response,
                new TypeReference<Result<List<Map<String, Object>>>>() {});
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        Map<String, Object> row = result.getData().stream()
                .filter(r -> ((Number) r.get("dish_id")).longValue() == 1L)
                .findFirst().orElseThrow(() -> new AssertionError("未返回插入的预测记录"));
        assertEquals("水煮鱼", row.get("dish_name"));
        assertEquals(50, ((Number) row.get("base_quantity")).intValue());
        assertEquals(60, ((Number) row.get("ai_suggest_quantity")).intValue());
        assertEquals(0, ((Number) row.get("status")).intValue());
        assertTrue(row.containsKey("predict_date"));
        assertTrue(row.containsKey("final_quantity"));
        assertTrue(row.containsKey("confirmed_by"));
    }

    @Test
    public void testConfirmPredictionWithToken() throws Exception {
        PredictionRecord record = insertRecord(LocalDate.of(2026, 8, 4), 1L);

        PredictConfirmDTO dto = new PredictConfirmDTO();
        dto.setRecordId(record.getId());
        dto.setFinalQuantity(100);
        // 前端伪造的 confirmedBy 必须被忽略，落库应为当前登录 ADMIN（userId=1）
        dto.setConfirmedBy(999L);

        String response = mockMvc.perform(post("/api/admin/predict/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        assertEquals(200, result.getCode());

        PredictionRecord updated = predictionRecordMapper.selectById(record.getId());
        assertEquals(100, updated.getFinalQuantity());
        assertEquals(1, updated.getStatus());
        assertEquals(ADMIN_ID, updated.getConfirmedBy());
    }

    @Test
    public void testConfirmPredictionIdempotentSameValue() throws Exception {
        PredictionRecord record = insertRecord(LocalDate.of(2026, 8, 5), 1L);

        PredictConfirmDTO dto = new PredictConfirmDTO();
        dto.setRecordId(record.getId());
        dto.setFinalQuantity(88);

        String body = objectMapper.writeValueAsString(dto);
        for (int i = 0; i < 2; i++) {
            String response = mockMvc.perform(post("/api/admin/predict/confirm")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            Result<Map<String, Object>> result = objectMapper.readValue(response,
                    new TypeReference<Result<Map<String, Object>>>() {});
            // 重复确认同一值视为成功
            assertEquals(200, result.getCode());
        }
        PredictionRecord updated = predictionRecordMapper.selectById(record.getId());
        assertEquals(88, updated.getFinalQuantity());
        assertEquals(1, updated.getStatus());
    }

    @Test
    public void testConfirmPredictionRecordNotFound() throws Exception {
        PredictConfirmDTO dto = new PredictConfirmDTO();
        dto.setRecordId(999999L);
        dto.setFinalQuantity(100);

        String response = mockMvc.perform(post("/api/admin/predict/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        // 记录不存在返回明确错误
        assertEquals(404, result.getCode());
        assertTrue(result.getMessage().contains("预测记录不存在"));
    }
}
