package com.smartkitchen.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartkitchen.common.Result;
import com.smartkitchen.config.JwtUtil;
import com.smartkitchen.dto.KnowledgeUploadDTO;
import com.smartkitchen.entity.KnowledgeDocument;
import com.smartkitchen.service.KnowledgeDocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

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

    @Test
    public void testUploadDocumentPythonDown_marksFailed() throws Exception {
        // Python 服务未启动：状态机应落 failed（可重新上传重试）
        KnowledgeUploadDTO dto = new KnowledgeUploadDTO();
        dto.setTitle("状态机测试文档");
        dto.setContent("状态机测试内容");
        dto.setVersion("9.9");

        String response = mockMvc.perform(post("/api/admin/knowledge/upload")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        // Python 若意外可用则跳过状态断言
        if (result.getCode() == 500) {
            KnowledgeDocument latest = knowledgeDocumentService.listDocuments().stream()
                    .filter(d -> "状态机测试文档".equals(d.getTitle()))
                    .findFirst().orElse(null);
            assertNotNull(latest);
            assertEquals("failed", latest.getStatus());
            assertNotNull(latest.getUpdateTime());
        }
    }

    @Test
    public void testArchiveDocument() throws Exception {
        // 先落一条 active 文档
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setTitle("待归档文档");
        doc.setChunkCount(1);
        doc.setVersion("1.0");
        doc.setStatus("active");
        doc.setUpdateTime(LocalDateTime.now());
        knowledgeDocumentService.save(doc);

        String response = mockMvc.perform(post("/api/admin/knowledge/" + doc.getId() + "/archive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<String> result = objectMapper.readValue(response,
                new TypeReference<Result<String>>() {});
        assertEquals(200, result.getCode());
        // Python 删除向量失败不阻塞归档（清理任务兜底），MySQL 必须已归档
        assertEquals("archived", knowledgeDocumentService.getById(doc.getId()).getStatus());
    }

    @Test
    public void testArchiveDocumentNotFound() throws Exception {
        String response = mockMvc.perform(post("/api/admin/knowledge/999999/archive")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        Result<Map<String, Object>> result = objectMapper.readValue(response,
                new TypeReference<Result<Map<String, Object>>>() {});
        assertEquals(404, result.getCode());
    }

    @Test
    public void testArchiveDocumentUnauthorized() throws Exception {
        mockMvc.perform(post("/api/admin/knowledge/1/archive"))
                .andExpect(status().isUnauthorized());
    }
}
