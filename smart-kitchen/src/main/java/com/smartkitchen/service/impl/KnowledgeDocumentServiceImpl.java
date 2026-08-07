package com.smartkitchen.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartkitchen.entity.KnowledgeDocument;
import com.smartkitchen.mapper.KnowledgeDocumentMapper;
import com.smartkitchen.service.KnowledgeDocumentService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG知识库文档服务实现类
 */
@Service
public class KnowledgeDocumentServiceImpl extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocument> implements KnowledgeDocumentService {

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
}
