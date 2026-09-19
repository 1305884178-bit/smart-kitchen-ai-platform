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

    KnowledgeDocument getDocument(Long id);

    KnowledgeDocument updateDocument(Long id, KnowledgeUploadDTO dto);

    /**
     * 解析上传的文件并清洗为纯文本（供前端预览回填后再走上传流程）。
     * 支持 .docx / .pdf / .png / .jpg；扫描版 PDF（文本层过短）与图片走 OCR；
     * 音频/视频等格式直接拒绝并给出明确错误。
     * @param file 上传的文件
     * @return 清洗后的纯文本
     */
    String parseFile(MultipartFile file);

    /**
     * 知识库上传状态机：processing → active / failed。
     * 1. MySQL 先落 status=processing 行（其 id 即 Milvus document_id，元数据与向量可互查）；
     * 2. 调 Python 向量化；
     * 3. 成功 → 置 active、回写分块数、DEL 知识库指纹；同名旧 active 版本置 archived 并删除其 Milvus 向量；
     * 4. 失败 → 置 failed（重新上传即重试）。
     * @param dto 上传请求（含标题、内容、版本、生效时间）
     * @return 落库后的文档实体
     */
    KnowledgeDocument uploadDocument(KnowledgeUploadDTO dto);

    /**
     * 归档/删除文档：MySQL 置 archived 并立即删除 Milvus 对应向量；
     * 向量删除失败不阻塞归档，由 Python 侧定时清理任务兜底。
     * @param id 文档ID
     * @return 文档是否存在
     */
    boolean archiveDocument(Long id);
}
