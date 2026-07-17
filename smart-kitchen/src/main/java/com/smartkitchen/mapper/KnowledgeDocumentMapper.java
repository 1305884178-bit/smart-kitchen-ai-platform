package com.smartkitchen.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartkitchen.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG文档元数据表 Mapper 接口
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {
}
