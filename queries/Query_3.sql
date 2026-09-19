ALTER TABLE ai_knowledge_document
    ADD COLUMN update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP
        COMMENT '更新时间（归档清理任务以此为归档时间口径）'
        AFTER create_time;