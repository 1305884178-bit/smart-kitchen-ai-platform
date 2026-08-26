package com.smartkitchen.service;

import com.smartkitchen.dto.KnowledgeUploadDTO;
import com.smartkitchen.entity.KnowledgeDocument;
import com.smartkitchen.mapper.KnowledgeDocumentMapper;
import com.smartkitchen.service.impl.KnowledgeDocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库元数据落库后应主动删除语义缓存指纹 key，避免最多等 60s TTL 才感知变更。
 */
@ExtendWith(MockitoExtension.class)
public class KnowledgeDocumentServiceTest {

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @InjectMocks
    private KnowledgeDocumentServiceImpl knowledgeDocumentService;

    @BeforeEach
    public void setUp() {
        ReflectionTestUtils.setField(knowledgeDocumentService, "baseMapper", knowledgeDocumentMapper);
    }

    @Test
    public void testSaveDocument_invalidatesKbVersionFingerprint() {
        when(knowledgeDocumentMapper.insert(any(KnowledgeDocument.class))).thenReturn(1);

        KnowledgeUploadDTO dto = new KnowledgeUploadDTO();
        dto.setTitle("招牌说明");
        dto.setVersion("2.0");
        dto.setStatus("active");

        knowledgeDocumentService.saveDocument(dto, 3);

        verify(stringRedisTemplate).delete("kb_version_fingerprint");
    }
}
