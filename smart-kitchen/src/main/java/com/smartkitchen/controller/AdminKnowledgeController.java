package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.dto.KnowledgeUploadDTO;
import com.smartkitchen.entity.KnowledgeDocument;
import com.smartkitchen.service.KnowledgeDocumentService;
import com.smartkitchen.service.PythonAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 管理端知识库控制器
 */
@RestController
@RequestMapping("/api/admin/knowledge")
public class AdminKnowledgeController {

    @Autowired
    private PythonAIService pythonAIService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    /**
     * 获取知识库文档列表
     * @return 文档列表
     */
    @GetMapping("/list")
    public Result<List<KnowledgeDocument>> listDocuments() {
        return Result.success(knowledgeDocumentService.listDocuments());
    }

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
