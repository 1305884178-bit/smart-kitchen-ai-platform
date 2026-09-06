package com.smartkitchen.service;

import com.smartkitchen.dto.KnowledgeUploadDTO;
import com.smartkitchen.dto.PredictConfirmDTO;
import com.smartkitchen.dto.PredictTriggerDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Python AI 服务代理接口
 */
public interface PythonAIService {

    /**
     * 触发备菜预测
     * @param dto 触发参数
     * @return Python返回结果
     */
    Map<String, Object> triggerPrediction(PredictTriggerDTO dto);

    /**
     * 查询预测结果
     * @param targetDate 预测日期
     * @return Python返回结果
     */
    Map<String, Object> getPredictionResult(String targetDate);

    /**
     * 查询预测任务状态
     * @param taskId 任务ID
     * @return Python返回结果
     */
    Map<String, Object> getPredictionStatus(String taskId);

    /**
     * 确认预测量
     * @param dto 确认参数
     * @return Python返回结果
     */
    Map<String, Object> confirmPrediction(PredictConfirmDTO dto);

    /**
     * 上传知识库文档（代理调用 Python /ai/knowledge/process）
     * @param dto 文档内容和元数据
     * @param documentId 文档ID（MySQL 元数据行 id，作为 Milvus document_id）
     * @return Python返回结果
     */
    Map<String, Object> uploadKnowledge(KnowledgeUploadDTO dto, String documentId);

    /**
     * 删除知识库文档向量（代理调用 Python /ai/knowledge/delete）
     * @param documentId 文档ID（Milvus document_id）
     * @return Python返回结果
     */
    Map<String, Object> deleteKnowledge(String documentId);

    /**
     * OCR 识别文件内容（代理调用 Python /ai/knowledge/ocr）
     * @param file 图片（png/jpg/jpeg）或扫描版 PDF
     * @return 识别出的纯文本
     */
    String ocrFile(MultipartFile file);
}
