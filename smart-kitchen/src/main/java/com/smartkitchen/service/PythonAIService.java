package com.smartkitchen.service;

import com.smartkitchen.dto.KnowledgeUploadDTO;
import com.smartkitchen.dto.PredictConfirmDTO;
import com.smartkitchen.dto.PredictTriggerDTO;

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
     * 上传知识库文档
     * @param dto 文档内容和元数据
     * @return Python返回结果
     */
    Map<String, Object> uploadKnowledge(KnowledgeUploadDTO dto);
}
