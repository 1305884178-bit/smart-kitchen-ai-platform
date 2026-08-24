package com.smartkitchen.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.common.Result;
import com.smartkitchen.config.JwtUtil;
import com.smartkitchen.dto.KnowledgeUploadDTO;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理端知识库控制器集成测试
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class AdminKnowledgeControllerIntegrationTest {

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
    public void testUploadDocumentUnauthorized() throws Exception {
        KnowledgeUploadDTO dto = new KnowledgeUploadDTO();
        dto.setContent("测试文档内容");
        dto.setVersion("1.0");
        dto.setStatus("active");

        mockMvc.perform(post("/api/admin/knowledge/upload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void testUploadDocumentWithToken() throws Exception {
        KnowledgeUploadDTO dto = new KnowledgeUploadDTO();
        dto.setContent("测试文档内容");
        dto.setVersion("1.0");
        dto.setStatus("active");

        String response = mockMvc.perform(post("/api/admin/knowledge/upload")
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
