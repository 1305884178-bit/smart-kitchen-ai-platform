-- 清理旧数据（可选，方便重复运行）
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE `sys_user`;
TRUNCATE TABLE `pms_category`;
TRUNCATE TABLE `pms_dish`;
TRUNCATE TABLE `oms_order`;
TRUNCATE TABLE `oms_order_detail`;
SET FOREIGN_KEY_CHECKS = 1;

-- 0. 插入用户数据
INSERT INTO `sys_user` (`id`, `username`, `password`, `nickname`, `role`, `create_time`) VALUES
(1000, 'admin', '123456', '管理员', 'ADMIN', NOW()),
(1001, 'customer1', '123456', '顾客张三', 'CUSTOMER', NOW()),
(1002, 'customer2', '123456', '顾客李四', 'CUSTOMER', NOW());

-- 1. 插入菜品分类
INSERT INTO `pms_category` (`id`, `name`, `sort`, `create_time`) VALUES
(1, '招牌热菜', 1, NOW()),
(2, '爽口凉菜', 2, NOW()),
(3, '酒水饮料', 3, NOW());

-- 2. 插入菜品
-- 招牌热菜
INSERT INTO `pms_dish` (`id`, `name`, `category_id`, `price`, `status`, `daily_stock`, `alert_threshold`, `ingredients`, `create_time`, `update_time`) VALUES
(1, '水煮鱼', 1, 88.00, 1, 50, 10, '["草鱼", "黄豆芽", "辣椒", "花椒"]', NOW(), NOW()),
(2, '宫保鸡丁', 1, 38.00, 1, 100, 20, '["鸡胸肉", "花生米", "大葱", "干辣椒"]', NOW(), NOW()),
(3, '农家小炒肉', 1, 45.00, 1, 80, 15, '["五花肉", "青尖椒", "大蒜"]', NOW(), NOW());

-- 爽口凉菜
INSERT INTO `pms_dish` (`id`, `name`, `category_id`, `price`, `status`, `daily_stock`, `alert_threshold`, `ingredients`, `create_time`, `update_time`) VALUES
(4, '拍黄瓜', 2, 18.00, 1, 60, 10, '["黄瓜", "大蒜", "香醋", "香油"]', NOW(), NOW()),
(5, '凉拌木耳', 2, 22.00, 1, 40, 5, '["黑木耳", "洋葱", "香菜"]', NOW(), NOW());

-- 酒水饮料
INSERT INTO `pms_dish` (`id`, `name`, `category_id`, `price`, `status`, `daily_stock`, `alert_threshold`, `ingredients`, `create_time`, `update_time`) VALUES
(6, '可口可乐', 3, 5.00, 1, 200, 50, '[]', NOW(), NOW()),
(7, '鲜榨西瓜汁', 3, 28.00, 1, 30, 5, '["西瓜", "冰块"]', NOW(), NOW());


-- 3. 插入测试订单（注：PRD 中未单独定义 User 表，用户体系由 JWT 携带 user_id，此处使用模拟用户 ID：1001 和 1002）

-- 订单 A：状态为 ORDERED（0：已下单，厨房待制作）
INSERT INTO `oms_order` (`id`, `order_no`, `user_id`, `seat_number`, `total_amount`, `status`, `remark`, `create_time`, `update_time`) VALUES
(1, 'ORD202607170001', 1001, 'A05', 106.00, 0, '水煮鱼少放点辣，谢谢', NOW(), NOW());

-- 订单 A 的明细：水煮鱼 x1 (88) + 拍黄瓜 x1 (18)
INSERT INTO `oms_order_detail` (`id`, `order_id`, `dish_id`, `dish_name`, `quantity`, `price`, `is_added`, `create_time`) VALUES
(1, 1, 1, '水煮鱼', 1, 88.00, 0, NOW()),
(2, 1, 4, '拍黄瓜', 1, 18.00, 0, NOW());


-- 订单 B：状态为 SERVED（10：厨房已出餐，待顾客结账）
INSERT INTO `oms_order` (`id`, `order_no`, `user_id`, `seat_number`, `total_amount`, `status`, `remark`, `create_time`, `update_time`) VALUES
(2, 'ORD202607170002', 1002, 'B02', 48.00, 10, '饮料要常温的', DATE_SUB(NOW(), INTERVAL 30 MINUTE), DATE_SUB(NOW(), INTERVAL 30 MINUTE));

-- 订单 B 的明细：宫保鸡丁 x1 (38) + 可口可乐 x2 (5*2=10)
INSERT INTO `oms_order_detail` (`id`, `order_id`, `dish_id`, `dish_name`, `quantity`, `price`, `is_added`, `create_time`) VALUES
(3, 2, 2, '宫保鸡丁', 1, 38.00, 0, DATE_SUB(NOW(), INTERVAL 30 MINUTE)),
(4, 2, 6, '可口可乐', 2, 5.00, 0, DATE_SUB(NOW(), INTERVAL 30 MINUTE));
