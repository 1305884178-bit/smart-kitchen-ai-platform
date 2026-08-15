package com.smartkitchen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartkitchen.dto.KnowledgeUploadDTO;
import com.smartkitchen.entity.KnowledgeDocument;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * RAG知识库文档服务接口
 */
public interface KnowledgeDocumentService extends IService<KnowledgeDocument> {

    /**
     * 查询所有知识库文档列表
     * @return 文档列表
     */
    List<KnowledgeDocument> listDocuments();

    /**
     * 解析上传的 Word(.docx) 或 PDF 文件，提取纯文本内容
     * @param file 上传的文件
     * @return 提取出的纯文本
     */
    String parseFile(MultipartFile file);

    /**
     * 保存知识库文档元数据到 MySQL
     * @param dto 上传请求（含标题、版本、状态、生效时间）
     * @param chunkCount 向量化分块数
     */
    void saveDocument(KnowledgeUploadDTO dto, int chunkCount);
}
