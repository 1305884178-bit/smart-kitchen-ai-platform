package com.smartkitchen.service;

import com.smartkitchen.dto.KnowledgeUploadDTO;
import com.smartkitchen.entity.KnowledgeDocument;
import com.smartkitchen.mapper.KnowledgeDocumentMapper;
import com.smartkitchen.service.impl.KnowledgeDocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 知识库写入状态机与文本清洗测试：
 * - 上传：processing → active（成功）/ failed（失败），指纹缓存主动失效；
 * - 新版本激活后旧版本归档并删除旧向量；
 * - 归档立即删向量；
 * - cleanText 去页眉页脚/页码/多余空行；
 * - parseFile 拒绝音频视频。
 */
@ExtendWith(MockitoExtension.class)
public class KnowledgeDocumentServiceTest {

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private PythonAIService pythonAIService;

    @InjectMocks
    private KnowledgeDocumentServiceImpl knowledgeDocumentService;

    @BeforeEach
    public void setUp() {
        ReflectionTestUtils.setField(knowledgeDocumentService, "baseMapper", knowledgeDocumentMapper);
    }

    private KnowledgeUploadDTO newDto() {
        KnowledgeUploadDTO dto = new KnowledgeUploadDTO();
        dto.setTitle("招牌说明");
        dto.setContent("黑叉烧是本店招牌，蜜汁口味。");
        dto.setVersion("2.0");
        return dto;
    }

    private void mockInsertAssignsId(long id) {
        when(knowledgeDocumentMapper.insert(any(KnowledgeDocument.class))).thenAnswer(inv -> {
            KnowledgeDocument d = inv.getArgument(0);
            d.setId(id);
            return 1;
        });
    }

    @Test
    public void testUpload_success_becomesActiveAndInvalidatesFingerprint() {
        mockInsertAssignsId(100L);
        when(pythonAIService.uploadKnowledge(any(KnowledgeUploadDTO.class), eq("100")))
                .thenReturn(Map.of("chunk_count", 3));

        KnowledgeDocument doc = knowledgeDocumentService.uploadDocument(newDto());

        assertEquals("active", doc.getStatus());
        assertEquals(3, doc.getChunkCount());
        assertNotNull(doc.getUpdateTime());
        // document_id 与 MySQL 行 id 一致
        verify(pythonAIService).uploadKnowledge(any(KnowledgeUploadDTO.class), eq("100"));
        // 落 processing 与 active 至少各失效一次指纹
        verify(stringRedisTemplate, atLeast(2)).delete("kb_version_fingerprint");
    }

    @Test
    public void testUpload_processingFirst() {
        // 首次落库必须是 processing（向量化完成前 C 端不可见的元数据口径）；
        // 注意实体后续会被原地改成 active，需在 insert 当下记录快照
        String[] statusAtInsert = new String[1];
        when(knowledgeDocumentMapper.insert(any(KnowledgeDocument.class))).thenAnswer(inv -> {
            KnowledgeDocument d = inv.getArgument(0);
            statusAtInsert[0] = d.getStatus();
            d.setId(100L);
            return 1;
        });
        when(pythonAIService.uploadKnowledge(any(), anyString())).thenReturn(Map.of("chunk_count", 1));

        knowledgeDocumentService.uploadDocument(newDto());

        assertEquals("processing", statusAtInsert[0]);
    }

    @Test
    public void testUpload_pythonFailure_marksFailed() {
        mockInsertAssignsId(100L);
        when(pythonAIService.uploadKnowledge(any(), anyString()))
                .thenThrow(new RuntimeException("connection refused"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> knowledgeDocumentService.uploadDocument(newDto()));
        assertTrue(ex.getMessage().contains("Python AI服务调用失败"));

        ArgumentCaptor<KnowledgeDocument> captor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(knowledgeDocumentMapper, atLeastOnce()).updateById(captor.capture());
        assertEquals("failed", captor.getValue().getStatus());
        verify(stringRedisTemplate, atLeastOnce()).delete("kb_version_fingerprint");
    }

    @Test
    public void testUpload_newVersion_archivesOldAndDeletesOldVectors() {
        mockInsertAssignsId(101L);
        when(pythonAIService.uploadKnowledge(any(), eq("101"))).thenReturn(Map.of("chunk_count", 2));
        // 同名旧 active 版本 id=50
        KnowledgeDocument old = new KnowledgeDocument();
        old.setId(50L);
        old.setTitle("招牌说明");
        old.setStatus("active");
        when(knowledgeDocumentMapper.selectList(any())).thenReturn(List.of(old));

        knowledgeDocumentService.uploadDocument(newDto());

        assertEquals("archived", old.getStatus());
        assertNotNull(old.getUpdateTime());
        verify(pythonAIService).deleteKnowledge("50");
    }

    @Test
    public void testArchiveDocument_marksArchivedAndDeletesVectors() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(7L);
        doc.setStatus("active");
        when(knowledgeDocumentMapper.selectById(7L)).thenReturn(doc);

        boolean ok = knowledgeDocumentService.archiveDocument(7L);

        assertTrue(ok);
        assertEquals("archived", doc.getStatus());
        verify(pythonAIService).deleteKnowledge("7");
        verify(stringRedisTemplate).delete("kb_version_fingerprint");
    }

    @Test
    public void testArchiveDocument_notFound() {
        when(knowledgeDocumentMapper.selectById(999L)).thenReturn(null);

        assertFalse(knowledgeDocumentService.archiveDocument(999L));
        verify(pythonAIService, never()).deleteKnowledge(anyString());
    }

    @Test
    public void testArchiveDocument_vectorDeleteFailureDoesNotBlock() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(7L);
        doc.setStatus("active");
        when(knowledgeDocumentMapper.selectById(7L)).thenReturn(doc);
        doThrow(new RuntimeException("milvus down")).when(pythonAIService).deleteKnowledge("7");

        assertTrue(knowledgeDocumentService.archiveDocument(7L));  // 归档不阻塞，清理任务兜底
        assertEquals("archived", doc.getStatus());
    }

    // ---------- cleanText ----------

    @Test
    public void testCleanText_removesPageNumbersAndCollapsesBlankLines() {
        String raw = "菜品知识卡：黑叉烧\r\n\r\n\r\n- 1 -\n蜜汁浓郁，甜咸平衡。\n\n\n\n第 2 页\n建议趁热食用。";
        String cleaned = KnowledgeDocumentServiceImpl.cleanText(raw);
        assertFalse(cleaned.contains("- 1 -"));
        assertFalse(cleaned.contains("第 2 页"));
        assertFalse(cleaned.contains("\n\n\n"));
        assertTrue(cleaned.contains("蜜汁浓郁"));
        assertTrue(cleaned.contains("建议趁热食用"));
    }

    @Test
    public void testCleanText_removesRepeatedHeaderFooterAcrossPages() {
        // 3 页，每页都有同样页眉页脚；正文各不同
        String page1 = "智慧后厨知识库\n玉米萝卜排骨汤正文\n内部资料 请勿外传\n\f";
        String page2 = "智慧后厨知识库\n干炒牛河正文\n内部资料 请勿外传\n\f";
        String page3 = "智慧后厨知识库\n黑叉烧正文\n内部资料 请勿外传\n\f";
        String cleaned = KnowledgeDocumentServiceImpl.cleanText(page1 + page2 + page3);
        assertFalse(cleaned.contains("智慧后厨知识库"));
        assertFalse(cleaned.contains("内部资料"));
        assertTrue(cleaned.contains("玉米萝卜排骨汤正文"));
        assertTrue(cleaned.contains("干炒牛河正文"));
        assertTrue(cleaned.contains("黑叉烧正文"));
    }

    @Test
    public void testCleanText_singlePageKeepsContent() {
        // 单页文档不做页眉页脚误判
        String raw = "菜品知识卡：铁观音\n兰花香高扬，回甘持久。";
        String cleaned = KnowledgeDocumentServiceImpl.cleanText(raw);
        assertTrue(cleaned.contains("菜品知识卡：铁观音"));
        assertTrue(cleaned.contains("兰花香高扬"));
    }

    @Test
    public void testParseFile_rejectsAudioVideo() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("介绍视频.mp4");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> knowledgeDocumentService.parseFile(file));
        assertTrue(ex.getMessage().contains("不支持音频/视频"));
    }

    @Test
    public void testParseFile_rejectsUnknownFormat() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("data.xlsx");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> knowledgeDocumentService.parseFile(file));
        assertTrue(ex.getMessage().contains("仅支持"));
    }

    @Test
    public void testParseFile_imageDelegatesToOcr() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("菜单照片.png");
        when(pythonAIService.ocrFile(file)).thenReturn("识别出的文字");

        assertEquals("识别出的文字", knowledgeDocumentService.parseFile(file));
        verify(pythonAIService).ocrFile(file);
    }
}
