# 本地启动与数据库说明

## 前置环境

- JDK 21、Maven 3.9+、Node.js 20+、Python 3.11+
- MySQL 8、Redis 7、RabbitMQ 3、微信开发者工具

RabbitMQ 未启动时普通 HTTP 接口仍可使用，但支付超时消息会持续重连，不能完整验证超时关单。

## 配置文件

```bash
cp smart-kitchen/.env.example smart-kitchen/.env
cp smart-kitchen-ai/.env.example smart-kitchen-ai/.env
```

生产环境必须替换 `JWT_SECRET`、`AI_INTERNAL_TOKEN`、微信凭证、数据库密码与模型密钥；这些值不得提交到 Git。

## 数据库初始化与升级

1. 创建 `smart_kitchen` 数据库，使用 `utf8mb4`。
2. 执行 `smart-kitchen/src/main/resources/db/schema.sql`。
3. 演示数据按需执行 `smart-kitchen/src/main/resources/db/data-test.sql`。
4. 已部署旧库按顺序执行 `queries/` 中的增量脚本；[Query_3.sql](../queries/Query_3.sql) 用于补充 `ai_knowledge_document.update_time`。

执行升级前请备份数据库，并记录执行时间与执行人。

## 启动

建议依次启动 MySQL、Redis、RabbitMQ、Java、AI、管理端，再在微信开发者工具导入 `miniprogram/`。

```bash
cd smart-kitchen && mvn spring-boot:run
cd smart-kitchen-ai && .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
cd admin-web && npm install && npm run dev -- --host 0.0.0.0
```

## 常见联调问题

- Java 返回 `401`：请求缺少登录 Token，不一定是服务异常。
- RabbitMQ 连接拒绝：确认服务运行于 `localhost:5672`。
- AI 多菜查询：用真实菜名提问“菜 A 和菜 B 还有吗，配料是什么？”。
- 小程序状态未更新：重新编译小程序；订单详情页停留时会刷新出餐状态。
