package com.smartkitchen.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.common.Result;
import com.smartkitchen.config.JwtUtil;
import com.smartkitchen.dto.PredictConfirmDTO;
import com.smartkitchen.dto.PredictTriggerDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B端备菜预测代理控制器集成测试
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

    private String token;

    @BeforeEach
    public void setup() {
        token = jwtUtil.generateToken(1L, "admin", "ADMIN");
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
        String response = mockMvc.perform(get("/api/admin/predict/result")
                        .header("Authorization", "Bearer " + token)
                        .param("targetDate", "2026-08-03"))
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
    public void testConfirmPredictionWithToken() throws Exception {
        PredictConfirmDTO dto = new PredictConfirmDTO();
        dto.setRecordId(1L);
        dto.setFinalQuantity(100);
        dto.setConfirmedBy(1L);

        String response = mockMvc.perform(post("/api/admin/predict/confirm")
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
}
