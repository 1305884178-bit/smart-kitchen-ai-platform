package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.dto.KnowledgeUploadDTO;
import com.smartkitchen.entity.KnowledgeDocument;
import com.smartkitchen.service.KnowledgeDocumentService;
import com.smartkitchen.service.PythonAIService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

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
     * 上传文档至知识库（代理调用Python /ai/knowledge/process 并保存元数据到 MySQL）
     * @param dto 文档内容和元数据
     * @return 处理结果
     */
    @PostMapping("/upload")
    public Result<Object> uploadDocument(@RequestBody KnowledgeUploadDTO dto) {
        try {
            Map<String, Object> pythonResult = pythonAIService.uploadKnowledge(dto);
            knowledgeDocumentService.saveDocument(dto, extractChunkCount(pythonResult));
            return Result.success(pythonResult);
        } catch (Exception e) {
            return Result.error(500, "Python AI服务调用失败：" + e.getMessage());
        }
    }

    /**
     * 解析上传的 Word(.docx) 或 PDF 文件，提取纯文本供前端回填
     * @param file 上传的文件
     * @return 提取出的纯文本
     */
    @PostMapping("/parse-file")
    public Result<String> parseFile(@RequestParam("file") MultipartFile file) {
        try {
            return Result.success(knowledgeDocumentService.parseFile(file));
        } catch (Exception e) {
            return Result.error(500, "文件解析失败：" + e.getMessage());
        }
    }

    /**
     * 从 Python 返回结果中提取分块数
     * @param pythonResult Python /ai/knowledge/process 返回结果
     * @return 分块数，缺失时返回 0
     */
    private int extractChunkCount(Map<String, Object> pythonResult) {
        if (pythonResult == null) {
            return 0;
        }
        Object chunkCount = pythonResult.get("chunk_count");
        if (chunkCount instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
