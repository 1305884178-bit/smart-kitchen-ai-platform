-- 6. AI备菜预测记录表
CREATE TABLE IF NOT EXISTS `ai_prediction_record` (
                                                      `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增',
                                                      `predict_date` date NOT NULL COMMENT '预测目标日期',
                                                      `dish_id` bigint NOT NULL COMMENT '菜品ID',
                                                      `base_quantity` int NOT NULL COMMENT '时序基础预测',
                                                      `ai_suggest_quantity` int NOT NULL COMMENT 'AI建议量',
                                                      `final_quantity` int NOT NULL COMMENT '人工确认量',
                                                      `reasoning` text COMMENT 'AI推理过程',
                                                      `confidence` decimal(3,2) DEFAULT NULL COMMENT 'AI置信度',
                                                      `recent_avg_score` decimal(2,1) DEFAULT NULL COMMENT '近30天评分均值',
                                                      `status` tinyint NOT NULL DEFAULT '0' COMMENT '0-待确认 / 1-已确认',
                                                      `confirmed_by` bigint DEFAULT NULL COMMENT '确认人',
                                                      `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                      PRIMARY KEY (`id`),
                                                      UNIQUE KEY `uk_date_dish` (`predict_date`, `dish_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI备菜预测记录表';

-- 7. RAG文档元数据表
CREATE TABLE IF NOT EXISTS `ai_knowledge_document` (
                                                       `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增',
                                                       `file_name` varchar(128) NOT NULL COMMENT '原始文件名',
                                                       `file_url` varchar(256) NOT NULL COMMENT '文件路径',
                                                       `chunk_count` int NOT NULL DEFAULT '0' COMMENT '分块数',
                                                       `version` int NOT NULL DEFAULT '1' COMMENT '版本号',
                                                       `status` varchar(16) NOT NULL DEFAULT 'draft' COMMENT 'draft/active/archived',
                                                       `effective_from` datetime DEFAULT NULL COMMENT '生效时间',
                                                       `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
                                                       PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG文档元数据表';