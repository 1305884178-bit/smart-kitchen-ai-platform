package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.dto.KnowledgeUploadDTO;
import com.smartkitchen.service.PythonAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端知识库控制器
 */
@RestController
@RequestMapping("/api/admin/knowledge")
public class AdminKnowledgeController {

    @Autowired
    private PythonAIService pythonAIService;

    /**
     * 上传文档至知识库（代理调用Python /ai/knowledge/process）
     * @param dto 文档内容和元数据
     * @return 处理结果
     */
    @PostMapping("/upload")
    public Result<Object> uploadDocument(@RequestBody KnowledgeUploadDTO dto) {
        try {
            return Result.success(pythonAIService.uploadKnowledge(dto));
        } catch (Exception e) {
            return Result.error(500, "Python AI服务调用失败：" + e.getMessage());
        }
    }
}
