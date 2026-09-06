package com.smartkitchen.service.impl;

import com.smartkitchen.dto.KnowledgeUploadDTO;
import com.smartkitchen.dto.PredictConfirmDTO;
import com.smartkitchen.dto.PredictTriggerDTO;
import com.smartkitchen.service.PythonAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Python AI 服务代理实现，封装对 Python FastAPI 的 RestTemplate 调用
 */
@Service
public class PythonAIServiceImpl implements PythonAIService {

    @Autowired
    private RestTemplate restTemplate;

    @Value("${smart-kitchen.python-service.url}")
    private String pythonServiceUrl;

    /** 服务间内部 token：与 Python 侧 AI_INTERNAL_TOKEN 配对，配置后随请求携带 */
    @Value("${smart-kitchen.python-service.internal-token:}")
    private String internalToken;

    private HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        if (internalToken != null && !internalToken.isEmpty()) {
            headers.setBearerAuth(internalToken);
        }
        return new HttpEntity<>(body, headers);
    }

    /**
     * 触发备菜预测（代理调用 Python /ai/predict/trigger）
     * @param dto 可选的targetDate和dishId
     * @return Python返回结果
     */
    @Override
    public Map<String, Object> triggerPrediction(PredictTriggerDTO dto) {
        String url = pythonServiceUrl + "/ai/predict/trigger";
        Map<String, Object> requestBody = new HashMap<>();
        if (dto.getTargetDate() != null) {
            requestBody.put("target_date", dto.getTargetDate());
        }
        if (dto.getDishId() != null) {
            requestBody.put("dish_id", dto.getDishId());
        }
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(requestBody),
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        return response.getBody();
    }

    /**
     * 查询备菜预测结果（代理调用 Python /ai/predict/result）
     * @param targetDate 预测日期
     * @return Python返回结果
     */
    @Override
    public Map<String, Object> getPredictionResult(String targetDate) {
        String url = pythonServiceUrl + "/ai/predict/result?target_date=" + targetDate;
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        return response.getBody();
    }

    /**
     * 查询预测任务状态（代理调用 Python /ai/predict/status）
     * @param taskId 任务ID
     * @return Python返回结果
     */
    @Override
    public Map<String, Object> getPredictionStatus(String taskId) {
        String url = pythonServiceUrl + "/ai/predict/status?task_id=" + taskId;
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        return response.getBody();
    }

    /**
     * 确认/覆盖预测量（代理调用 Python /ai/predict/confirm）
     * @param dto 包含recordId、finalQuantity、confirmedBy
     * @return Python返回结果
     */
    @Override
    public Map<String, Object> confirmPrediction(PredictConfirmDTO dto) {
        String url = pythonServiceUrl + "/ai/predict/confirm";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("record_id", dto.getRecordId());
        requestBody.put("final_quantity", dto.getFinalQuantity());
        requestBody.put("confirmed_by", dto.getConfirmedBy());
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(requestBody),
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        return response.getBody();
    }

    /**
     * 上传文档至知识库（代理调用 Python /ai/knowledge/process）
     * @param dto 文档内容和元数据
     * @param documentId 文档ID（MySQL 元数据行 id，作为 Milvus document_id）
     * @return Python返回结果
     */
    @Override
    public Map<String, Object> uploadKnowledge(KnowledgeUploadDTO dto, String documentId) {
        String url = pythonServiceUrl + "/ai/knowledge/process";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("content", dto.getContent());
        Map<String, Object> metadata = dto.getMetadata() != null ? new HashMap<>(dto.getMetadata()) : new HashMap<>();
        // 标题写入 chunk 元数据，供 RAG 溯源展示
        if (dto.getTitle() != null && !dto.getTitle().isEmpty()) {
            metadata.put("title", dto.getTitle());
        }
        requestBody.put("metadata", metadata);
        requestBody.put("version", dto.getVersion() != null ? dto.getVersion() : "1.0");
        requestBody.put("status", dto.getStatus() != null ? dto.getStatus() : "active");
        requestBody.put("effective_from", dto.getEffectiveFrom() != null ? dto.getEffectiveFrom().toString() : null);
        requestBody.put("document_id", documentId);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, HttpMethod.POST,
                jsonEntity(requestBody),
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        return response.getBody();
    }

    /**
     * 删除知识库文档向量（代理调用 Python /ai/knowledge/delete）
     * @param documentId 文档ID（Milvus document_id）
     * @return Python返回结果
     */
    @Override
    public Map<String, Object> deleteKnowledge(String documentId) {
        String url = pythonServiceUrl + "/ai/knowledge/delete";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("document_id", documentId);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, HttpMethod.POST,
                jsonEntity(requestBody),
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        return response.getBody();
    }

    /**
     * OCR 识别文件内容（代理调用 Python /ai/knowledge/ocr）
     * @param file 图片（png/jpg/jpeg）或扫描版 PDF
     * @return 识别出的纯文本
     */
    @Override
    public String ocrFile(MultipartFile file) {
        String url = pythonServiceUrl + "/ai/knowledge/ocr";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (internalToken != null && !internalToken.isEmpty()) {
            headers.setBearerAuth(internalToken);
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        try {
            ByteArrayResource resource = new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename();
                }
            };
            body.add("file", resource);
        } catch (IOException e) {
            throw new RuntimeException("读取上传文件失败：" + e.getMessage(), e);
        }
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        Map<String, Object> responseBody = response.getBody();
        if (responseBody == null || responseBody.get("text") == null) {
            throw new RuntimeException("OCR 服务返回为空");
        }
        return String.valueOf(responseBody.get("text"));
    }
}
