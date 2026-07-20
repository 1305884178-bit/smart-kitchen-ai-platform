USE `smart_kitchen`;

-- 1. 创建系统用户表
CREATE TABLE IF NOT EXISTS `sys_user` (
                                          `id` bigint NOT NULL AUTO_INCREMENT,
                                          `openid` varchar(64) DEFAULT NULL COMMENT '微信openid（可为空）',
                                          `username` varchar(32) DEFAULT NULL COMMENT '登录账号/手机号',
                                          `password` varchar(128) DEFAULT NULL COMMENT '密码（PC端备用）',
                                          `nickname` varchar(32) DEFAULT NULL COMMENT '昵称',
                                          `avatar` varchar(256) DEFAULT NULL COMMENT '头像',
                                          `role` varchar(16) NOT NULL DEFAULT 'CUSTOMER' COMMENT 'CUSTOMER / ADMIN',
                                          `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                          PRIMARY KEY (`id`),
                                          UNIQUE KEY `uk_openid` (`openid`),
                                          UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统用户表';

-- 2. 清理表数据（可选，如果是空表可不执行）
TRUNCATE TABLE `sys_user`;

-- 3. 插入测试用户数据（用于账号密码登录测试）
INSERT INTO `sys_user` (`id`, `username`, `password`, `nickname`, `role`, `create_time`) VALUES
                                                                                             (1000, 'admin', '123456', '管理员', 'ADMIN', NOW()),
                                                                                             (1001, 'customer1', '123456', '顾客张三', 'CUSTOMER', NOW()),
                                                                                             (1002, 'customer2', '123456', '顾客李四', 'CUSTOMER', NOW());