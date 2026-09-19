ALTER TABLE ai_knowledge_document
    ADD COLUMN content LONGTEXT NULL
        COMMENT '文档原文（管理端查看与编辑使用）'
        AFTER title;
