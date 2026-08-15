ALTER TABLE ai_knowledge_document
    DROP COLUMN file_url,
    CHANGE COLUMN file_name title VARCHAR(128) NOT NULL COMMENT '文档标题';