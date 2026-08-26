package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartkitchen.dto.KnowledgeUploadDTO;
import com.smartkitchen.entity.KnowledgeDocument;
import com.smartkitchen.mapper.KnowledgeDocumentMapper;
import com.smartkitchen.service.KnowledgeDocumentService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * RAG知识库文档服务实现类
 */
@Service
public class KnowledgeDocumentServiceImpl extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocument> implements KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentServiceImpl.class);

    /** 与 Python semantic_cache_service.KB_VERSION_CACHE_KEY 保持一致 */
    private static final String KB_VERSION_CACHE_KEY = "kb_version_fingerprint";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询所有知识库文档列表，按创建时间降序排列
     * @return 文档列表
     */
    @Override
    public List<KnowledgeDocument> listDocuments() {
        LambdaQueryWrapper<KnowledgeDocument> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(KnowledgeDocument::getCreateTime);
        return list(wrapper);
    }

    /**
     * 解析上传的 Word(.docx) 或 PDF 文件，提取纯文本内容
     * @param file 上传的文件
     * @return 提取出的纯文本
     */
    @Override
    public String parseFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("上传文件不能为空");
        }
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new RuntimeException("文件名缺失");
        }
        String lowerName = filename.toLowerCase();
        try {
            if (lowerName.endsWith(".docx")) {
                return parseDocx(file);
            }
            if (lowerName.endsWith(".pdf")) {
                return parsePdf(file);
            }
            throw new RuntimeException("仅支持 .docx 或 .pdf 格式的文件");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("文件解析失败：" + e.getMessage());
        }
    }

    /**
     * 解析 .docx 文件提取纯文本
     * @param file 上传的 docx 文件
     * @return 提取出的文本
     */
    private String parseDocx(MultipartFile file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(file.getInputStream());
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    /**
     * 解析 PDF 文件提取纯文本
     * @param file 上传的 pdf 文件
     * @return 提取出的文本
     */
    private String parsePdf(MultipartFile file) throws IOException {
        try (PDDocument document = PDDocument.load(file.getInputStream())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * 保存知识库文档元数据到 MySQL
     * @param dto 上传请求（含文件名、版本、状态、生效时间）
     * @param chunkCount 向量化分块数
     */
    @Override
    public void saveDocument(KnowledgeUploadDTO dto, int chunkCount) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTitle(dto.getTitle() != null && !dto.getTitle().isEmpty()
                ? dto.getTitle() : "未命名文档");
        document.setChunkCount(chunkCount);
        // 版本号是标签而非数值（AI 侧按字符串做 version == "v2.0" 过滤），原样保存保持前后一致
        document.setVersion(dto.getVersion() != null && !dto.getVersion().isEmpty()
                ? dto.getVersion() : "1.0");
        document.setStatus(dto.getStatus() != null ? dto.getStatus() : "draft");
        document.setEffectiveFrom(dto.getEffectiveFrom());
        this.save(document);
        // MySQL 元数据已变，主动删指纹缓存；保留 60s TTL 作兜底，避免最长等一分钟才感知变更
        invalidateKbVersionFingerprint();
    }

    private void invalidateKbVersionFingerprint() {
        try {
            stringRedisTemplate.delete(KB_VERSION_CACHE_KEY);
        } catch (Exception e) {
            log.warn("删除知识库版本指纹缓存失败（不影响上传）: {}", e.getMessage());
        }
    }
}
