-- 创建数据库
CREATE DATABASE IF NOT EXISTS `smart_kitchen` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `smart_kitchen`;

-- 1. 订单主表
CREATE TABLE IF NOT EXISTS `oms_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增',
  `order_no` varchar(32) NOT NULL COMMENT '订单号',
  `user_id` bigint NOT NULL COMMENT '下单用户',
  `seat_number` varchar(8) DEFAULT NULL COMMENT '座位号（如 A05）',
  `total_amount` decimal(10,2) NOT NULL COMMENT '订单总额',
  `status` tinyint NOT NULL COMMENT '0-ORDERED / 10-SERVED / 20-PAID / 90-CANCELLED',
  `cancel_reason` varchar(16) DEFAULT NULL COMMENT '撤销原因，如 MERCHANT_CANCEL',
  `pay_time` datetime DEFAULT NULL COMMENT '支付时间（结账时填充）',
  `payment_trade_no` varchar(64) DEFAULT NULL COMMENT '支付流水号（幂等键）',
  `complete_time` datetime DEFAULT NULL COMMENT '厨房完成时间',
  `operator_id` bigint DEFAULT NULL COMMENT '最后操作管理员',
  `remark` varchar(256) DEFAULT NULL COMMENT '顾客备注',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_no` (`order_no`),
  UNIQUE KEY `uk_payment_trade_no` (`payment_trade_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单主表';

-- 2. 订单明细表
CREATE TABLE IF NOT EXISTS `oms_order_detail` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增',
  `order_id` bigint NOT NULL COMMENT '关联订单',
  `dish_id` bigint NOT NULL COMMENT '菜品ID',
  `dish_name` varchar(64) NOT NULL COMMENT '菜品名称快照',
  `quantity` int NOT NULL COMMENT '数量',
  `price` decimal(10,2) NOT NULL COMMENT '购买时快照价格',
  `is_added` tinyint NOT NULL DEFAULT '0' COMMENT '0-首单 / 1-加菜追加',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订单明细表';

-- 3. 评价表
CREATE TABLE IF NOT EXISTS `oms_review` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增',
  `order_id` bigint NOT NULL COMMENT '关联订单（一单一评）',
  `user_id` bigint NOT NULL COMMENT '评价用户',
  `score` tinyint NOT NULL COMMENT '1-5星',
  `comment` varchar(512) DEFAULT NULL COMMENT '文字评价（可选）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评价表';

-- 4. 菜品表
CREATE TABLE IF NOT EXISTS `pms_dish` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增',
  `name` varchar(64) NOT NULL COMMENT '菜品名称',
  `category_id` bigint NOT NULL COMMENT '分类ID',
  `price` decimal(10,2) NOT NULL COMMENT '售价',
  `image` varchar(256) DEFAULT NULL COMMENT '图片URL',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '1-起售 / 0-停售',
  `daily_stock` int NOT NULL DEFAULT '0' COMMENT '每日库存',
  `alert_threshold` int NOT NULL DEFAULT '0' COMMENT '预警阈值',
  `ingredients` text COMMENT '配料JSON数组',
  `new_product_initial_stock` int DEFAULT '0' COMMENT '新品初始库存',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品表';

-- 5. 菜品分类表
CREATE TABLE IF NOT EXISTS `pms_category` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增',
  `name` varchar(32) NOT NULL COMMENT '分类名',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品分类表';

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

-- 8. 库存变更流水表
CREATE TABLE IF NOT EXISTS `inv_stock_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '自增',
  `dish_id` bigint NOT NULL COMMENT '菜品ID',
  `change_type` varchar(16) NOT NULL COMMENT 'RESERVE/DEDUCT/ROLLBACK/MANUAL',
  `change_qty` int NOT NULL COMMENT '变更量',
  `before_qty` int NOT NULL COMMENT '变更前',
  `after_qty` int NOT NULL COMMENT '变更后',
  `order_no` varchar(32) DEFAULT NULL COMMENT '关联订单号',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_dish_id` (`dish_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='库存变更流水表';
