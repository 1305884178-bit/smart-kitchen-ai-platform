package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartkitchen.dto.KnowledgeUploadDTO;
import com.smartkitchen.entity.KnowledgeDocument;
import com.smartkitchen.mapper.KnowledgeDocumentMapper;
import com.smartkitchen.service.KnowledgeDocumentService;
import com.smartkitchen.service.PythonAIService;
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
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG知识库文档服务实现类
 */
@Service
public class KnowledgeDocumentServiceImpl extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocument> implements KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentServiceImpl.class);

    /** 与 Python semantic_cache_service.KB_VERSION_CACHE_KEY 保持一致 */
    private static final String KB_VERSION_CACHE_KEY = "kb_version_fingerprint";

    /** PDF 文本层低于该长度判定为扫描件，转 OCR 识别 */
    private static final int MIN_PDF_TEXT_LENGTH = 50;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private PythonAIService pythonAIService;

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
     * 知识库上传状态机：processing → active / failed
     */
    @Override
    public KnowledgeDocument uploadDocument(KnowledgeUploadDTO dto) {
        // 1. MySQL 先落 processing 行；其 id 即 Milvus document_id
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTitle(dto.getTitle() != null && !dto.getTitle().isEmpty()
                ? dto.getTitle() : "未命名文档");
        document.setChunkCount(0);
        // 版本号是标签而非数值（AI 侧按字符串做 version == "v2.0" 过滤），原样保存保持前后一致
        document.setVersion(dto.getVersion() != null && !dto.getVersion().isEmpty()
                ? dto.getVersion() : "1.0");
        document.setStatus("processing");
        document.setEffectiveFrom(dto.getEffectiveFrom());
        document.setUpdateTime(LocalDateTime.now());
        this.save(document);
        invalidateKbVersionFingerprint();

        String documentId = String.valueOf(document.getId());
        try {
            // 2. Python 向量化（chunk 直接写 active；若此处之后失败，孤儿向量由清理任务补偿删除）
            Map<String, Object> pythonResult = pythonAIService.uploadKnowledge(dto, documentId);
            int chunkCount = extractChunkCount(pythonResult);

            // 3. 向量化成功 → active
            document.setStatus("active");
            document.setChunkCount(chunkCount);
            document.setUpdateTime(LocalDateTime.now());
            this.updateById(document);

            // 同一文档新版本激活后：旧版本归档并物理删除旧 chunk
            archivePreviousVersions(document);
            invalidateKbVersionFingerprint();
            return document;
        } catch (Exception e) {
            // 4. 失败 → failed，可重新上传重试
            document.setStatus("failed");
            document.setUpdateTime(LocalDateTime.now());
            this.updateById(document);
            invalidateKbVersionFingerprint();
            throw new RuntimeException("Python AI服务调用失败：" + e.getMessage(), e);
        }
    }

    /**
     * 同名旧 active 版本置 archived，并立即删除其 Milvus 向量（失败由清理任务兜底）
     */
    private void archivePreviousVersions(KnowledgeDocument newDoc) {
        List<KnowledgeDocument> oldActives = list(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getTitle, newDoc.getTitle())
                .eq(KnowledgeDocument::getStatus, "active")
                .ne(KnowledgeDocument::getId, newDoc.getId()));
        for (KnowledgeDocument old : oldActives) {
            old.setStatus("archived");
            old.setUpdateTime(LocalDateTime.now());
            this.updateById(old);
            deleteVectorSilently(old.getId());
        }
    }

    /**
     * 归档/删除文档：MySQL 置 archived 并立即删除 Milvus 对应向量
     */
    @Override
    public boolean archiveDocument(Long id) {
        KnowledgeDocument document = this.getById(id);
        if (document == null) {
            return false;
        }
        document.setStatus("archived");
        document.setUpdateTime(LocalDateTime.now());
        this.updateById(document);
        deleteVectorSilently(id);
        invalidateKbVersionFingerprint();
        return true;
    }

    private void deleteVectorSilently(Long id) {
        try {
            pythonAIService.deleteKnowledge(String.valueOf(id));
        } catch (Exception e) {
            log.warn("删除 Milvus 向量失败 document_id={}，由 Python 清理任务兜底: {}", id, e.getMessage());
        }
    }

    /**
     * 解析上传文件并清洗为纯文本
     * @param file 上传的文件
     * @return 清洗后的纯文本
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
                return cleanText(parseDocx(file));
            }
            if (lowerName.endsWith(".pdf")) {
                String text = cleanText(parsePdf(file));
                if (text.length() < MIN_PDF_TEXT_LENGTH) {
                    // 扫描版 PDF：文本层过短，转 OCR
                    log.info("PDF 文本层过短（{} 字符），转 OCR 识别: {}", text.length(), filename);
                    return pythonAIService.ocrFile(file);
                }
                return text;
            }
            if (lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
                return pythonAIService.ocrFile(file);
            }
            if (lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") || lowerName.endsWith(".m4a")
                    || lowerName.endsWith(".mp4") || lowerName.endsWith(".mov") || lowerName.endsWith(".avi")) {
                throw new RuntimeException("不支持音频/视频文件，请上传 Word(.docx)、PDF 或图片(png/jpg) 文档");
            }
            throw new RuntimeException("仅支持 .docx、.pdf、.png、.jpg 格式的文件");
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
            // 显式页分隔符（PDFBox 2.x 用 pageEnd），供清洗时识别跨页重复的页眉页脚
            stripper.setPageEnd("\f");
            return stripper.getText(document);
        }
    }

    /**
     * 轻量文本清洗（不引入版面模型）：
     * 1. 统一换行符、去行尾空白；
     * 2. 删除页码行（纯数字 / 第N页）；
     * 3. 删除跨页重复率 >=60% 的短行（页眉页脚，仅在页数 >=3 时启用，避免误伤单页文档）；
     * 4. 多个连续空行压缩为一个。
     */
    public static String cleanText(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace("\r", "\n");
        String[] pages = text.split("\f");

        // 统计短行跨页出现次数，识别页眉页脚
        Set<String> headersFooters = new HashSet<>();
        if (pages.length >= 3) {
            Map<String, Integer> linePageCount = new HashMap<>();
            for (String page : pages) {
                Set<String> seenInPage = new HashSet<>();
                for (String line : page.split("\n")) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && trimmed.length() <= 30) {
                        seenInPage.add(trimmed);
                    }
                }
                for (String s : seenInPage) {
                    linePageCount.merge(s, 1, Integer::sum);
                }
            }
            int threshold = Math.max(2, (int) Math.ceil(pages.length * 0.6));
            for (Map.Entry<String, Integer> entry : linePageCount.entrySet()) {
                if (entry.getValue() >= threshold) {
                    headersFooters.add(entry.getKey());
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        int blankRun = 0;
        for (String page : pages) {
            for (String line : page.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    blankRun++;
                    continue;
                }
                // 页码行：纯数字（可带连字符）或「第N页」
                if (trimmed.matches("[-—–\\s]*\\d{1,4}[-—–\\s]*")
                        || trimmed.matches("第\\s*\\d{1,4}\\s*页.*")) {
                    continue;
                }
                if (headersFooters.contains(trimmed)) {
                    continue;
                }
                if (blankRun > 0 && sb.length() > 0) {
                    sb.append("\n");
                }
                blankRun = 0;
                sb.append(trimmed).append("\n");
            }
        }
        return sb.toString().trim();
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

    private void invalidateKbVersionFingerprint() {
        try {
            stringRedisTemplate.delete(KB_VERSION_CACHE_KEY);
        } catch (Exception e) {
            log.warn("删除知识库版本指纹缓存失败（不影响上传）: {}", e.getMessage());
        }
    }
}
