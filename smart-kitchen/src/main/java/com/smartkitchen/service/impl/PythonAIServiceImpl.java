package com.smartkitchen.service.impl;

import com.smartkitchen.dto.KnowledgeUploadDTO;
import com.smartkitchen.dto.PredictConfirmDTO;
import com.smartkitchen.dto.PredictTriggerDTO;
import com.smartkitchen.service.PythonAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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
     * @return Python返回结果
     */
    @Override
    public Map<String, Object> uploadKnowledge(KnowledgeUploadDTO dto) {
        String url = pythonServiceUrl + "/ai/knowledge/process";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("content", dto.getContent());
        requestBody.put("metadata", dto.getMetadata() != null ? dto.getMetadata() : new HashMap<>());
        requestBody.put("version", dto.getVersion() != null ? dto.getVersion() : "1.0");
        requestBody.put("status", dto.getStatus() != null ? dto.getStatus() : "active");
        requestBody.put("effective_from", dto.getEffectiveFrom() != null ? dto.getEffectiveFrom().toString() : null);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(requestBody),
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );
        return response.getBody();
    }
}
