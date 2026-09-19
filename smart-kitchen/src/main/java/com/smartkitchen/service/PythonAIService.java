package com.smartkitchen.service;

import com.smartkitchen.dto.KnowledgeUploadDTO;
import com.smartkitchen.dto.PredictTriggerDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Python AI 服务代理接口。
 * 注意：预测结果的查询与人工确认已收归 Java 直查/直写 MySQL（见 PredictionService），
 * 此处仅保留进度仍在 Python/Redis 的 trigger/status 及知识库相关代理。
 */
public interface PythonAIService {

    /**
     * 触发备菜预测
     * @param dto 触发参数
     * @return Python返回结果
     */
    Map<String, Object> triggerPrediction(PredictTriggerDTO dto);

    /**
     * 查询预测任务状态（进度存于 Python 侧 Redis predict:task:*）
     * @param taskId 任务ID
     * @return Python返回结果
     */
    Map<String, Object> getPredictionStatus(String taskId);

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
     * 读取已向量化文档的原文。用于兼容内容字段上线前创建的历史知识。
     * @param documentId 文档ID（Milvus document_id）
     * @return 按分块顺序恢复的文档内容；不存在时返回空字符串
     */
    String getKnowledgeContent(String documentId, String title);

    /**
     * OCR 识别文件内容（代理调用 Python /ai/knowledge/ocr）
     * @param file 图片（png/jpg/jpeg）或扫描版 PDF
     * @return 识别出的纯文本
     */
    String ocrFile(MultipartFile file);
}
