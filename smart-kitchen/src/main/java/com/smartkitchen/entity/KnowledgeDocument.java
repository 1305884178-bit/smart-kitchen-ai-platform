package com.smartkitchen.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG文档元数据表实体类
 */
@Data
@TableName("ai_knowledge_document")
public class KnowledgeDocument {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String content;
    private Integer chunkCount;
    private String version;
    private String status;
    private LocalDateTime effectiveFrom;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
