package com.smartkitchen.controller;

import com.smartkitchen.common.Result;
import com.smartkitchen.dto.KnowledgeUploadDTO;
import com.smartkitchen.entity.KnowledgeDocument;
import com.smartkitchen.service.KnowledgeDocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 管理端知识库控制器
 */
@RestController
@RequestMapping("/api/admin/knowledge")
public class AdminKnowledgeController {

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

    @GetMapping("/{id}")
    public Result<KnowledgeDocument> getDocument(@PathVariable Long id) {
        KnowledgeDocument document = knowledgeDocumentService.getDocument(id);
        return document == null ? Result.error(404, "文档不存在") : Result.success(document);
    }

    @PutMapping("/{id}")
    public Result<KnowledgeDocument> updateDocument(@PathVariable Long id, @RequestBody KnowledgeUploadDTO dto) {
        try {
            KnowledgeDocument document = knowledgeDocumentService.updateDocument(id, dto);
            return document == null ? Result.error(404, "文档不存在") : Result.success(document);
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /**
     * 上传文档至知识库（状态机：MySQL processing → Python 向量化 → active/failed）
     * @param dto 文档内容和元数据
     * @return 处理结果
     */
    @PostMapping("/upload")
    public Result<Object> uploadDocument(@RequestBody KnowledgeUploadDTO dto) {
        try {
            KnowledgeDocument document = knowledgeDocumentService.uploadDocument(dto);
            return Result.success(document);
        } catch (Exception e) {
            return Result.error(500, e.getMessage());
        }
    }

    /**
     * 归档/删除文档：MySQL 置 archived 并立即删除 Milvus 对应向量
     * @param id 文档ID
     * @return 处理结果
     */
    @PostMapping("/{id}/archive")
    public Result<Object> archiveDocument(@PathVariable Long id) {
        boolean exists = knowledgeDocumentService.archiveDocument(id);
        if (!exists) {
            return Result.error(404, "文档不存在");
        }
        return Result.success("文档已归档，对应向量已删除");
    }

    /**
     * 解析上传的文件（Word/PDF/图片），提取并清洗为纯文本供前端预览回填
     * @param file 上传的文件
     * @return 清洗后的纯文本
     */
    @PostMapping("/parse-file")
    public Result<String> parseFile(@RequestParam("file") MultipartFile file) {
        try {
            return Result.success(knowledgeDocumentService.parseFile(file));
        } catch (Exception e) {
            return Result.error(500, "文件解析失败：" + e.getMessage());
        }
    }
}
