package com.smartkitchen.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * B端知识库上传请求DTO
 */
@Data
public class KnowledgeUploadDTO {
    private String content;
    private String title;
    private Map<String, Object> metadata;
    private String version;
    private String status;
    private LocalDateTime effectiveFrom;
}
