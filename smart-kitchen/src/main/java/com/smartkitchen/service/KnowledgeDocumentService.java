package com.smartkitchen.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartkitchen.entity.KnowledgeDocument;

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
}
