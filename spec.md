# 智慧后厨备菜与点单系统 - 技术规格说明书

## 1. 系统架构

```text
[ C端小程序 (Vue3) ]      [ B端厨房看板/后台 (Vue3) ]
        │                            │
        ├──────── (HTTP/RESTful) ────┤
        ├──────── (WebSocket) ───────┤
        ▼                            ▼
┌─────────────────────────────────────────────────┐
│              Spring Boot 核心服务层               │
│  [安全层] JWT Interceptor / GlobalExceptionHandler │
│  [业务层] OrderService / AuthService / ...        │
│  [基础层] Redis / MyBatis-Plus / WebSocket        │
└─────────────────────────────────────────────────┘
        │                            │
        ▼                            ▼
  [ MySQL 8.0 ]                 [ Redis ]
```

## 2. 技术栈

| 领域 | 技术选型 |
|---|---|
| 运行环境 | Java 21 + Spring Boot 3.2 |
| 持久层 | MyBatis-Plus + MySQL 8.0 |
| 缓存与并发 | Redis + Lua 脚本 |
| 实时通信 | Spring WebSocket |
| 安全认证 | JJWT + HandlerInterceptor |
| AI 编排 | Python FastAPI + LangGraph Supervisor（多智能体）|
| 向量检索 | Milvus（RAG知识库） |
| 消息队列 | RabbitMQ（订单超时延迟消息） |

## 3. 工程结构

```text
src/main/java/com/smartkitchen/
 ├── common/        # Result<T> 统一响应、枚举常量
 ├── config/        # WebSocket、JWT、CORS、拦截器注册
 ├── controller/    # REST 接口层
 ├── dto/           # 请求/响应数据传输对象
 ├── entity/        # 数据库实体（映射表）
 ├── exception/     # GlobalExceptionHandler
 ├── mapper/        # MyBatis Mapper 接口
 ├── service/       # 业务接口
 └── service/impl/  # 业务实现
```

## 4. 开发规范

1. **分层调用**：Controller → Service → Mapper，禁止跨层调用。
2. **统一响应体**：所有接口返回 `Result<T>`，格式 `{ code, message, data }`。
3. **异常处理**：业务异常通过 `throw new RuntimeException("msg")` 抛出，由 `GlobalExceptionHandler` 统一捕获转换为 `code: 500`。
4. **命名规范**：Controller 方法名反映动作（`list`、`add`、`update`、`delete`、`submit`、`pay`）。
5. **注释规范**：每个 public 方法前加 Javadoc，说明功能和参数含义。

## 5. 数据模型

### 5.1 pms_category（菜品分类表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | 自增 |
| name | varchar(32) | 分类名 |
| sort | int | 排序值 |
| create_time | datetime | - |

### 5.2 pms_dish（菜品表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | 自增 |
| name | varchar(64) | 菜品名称 |
| category_id | bigint | 分类ID |
| price | decimal(10,2) | 售价 |
| image | varchar(256) | 图片URL |
| status | tinyint | 1=起售 / 0=停售 |
| daily_stock | int | 每日库存 |
| alert_threshold | int | 预警阈值 |
| new_product_initial_stock | int | 新品初始库存（上架时管理员设定的预期日销量） |
| ingredients | text | 配料JSON数组 |
| create_time | datetime | - |
| update_time | datetime | - |

### 5.3 oms_order（订单主表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | 自增 |
| order_no | varchar(32) UNIQUE | 订单编号 |
| user_id | bigint | 顾客ID |
| seat_number | varchar(8) | 座位号 |
| total_amount | decimal(10,2) | 订单总金额 |
| status | tinyint | 0=ORDERED / 10=SERVED / 20=PAID / 90=CANCELLED |
| cancel_reason | varchar(64) | 撤销原因 |
| payment_trade_no | varchar(64) | 支付流水号（Mock支付为UUID） |
| pay_time | datetime | 结账时间 |
| complete_time | datetime | 出餐完成时间 |
| create_time | datetime | - |
| update_time | datetime | - |

### 5.4 oms_order_detail（订单明细表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | 自增 |
| order_id | bigint | 订单ID |
| dish_id | bigint | 菜品ID |
| dish_name | varchar(64) | 菜品名称快照 |
| quantity | int | 数量 |
| price | decimal(10,2) | 下单时快照价格 |
| is_added | tinyint | 0=首单 / 1=加菜 |
| create_time | datetime | - |

### 5.5 oms_review（评价表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | 自增 |
| order_id | bigint UNIQUE | 订单ID（一单一评） |
| user_id | bigint | 评价人 |
| score | tinyint | 1-5 星 |
| comment | varchar(512) | 文字评价 |
| create_time | datetime | - |

### 5.6 sys_user（系统用户表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | 自增 |
| openid | varchar(64) UNIQUE | 微信openid（C端登录凭证） |
| username | varchar(32) UNIQUE | 账号（B端管理员使用，C端留空） |
| password | varchar(128) | 密码（B端管理员使用，C端留空） |
| phone | varchar(16) | 手机号 |
| nickname | varchar(32) | 昵称 |
| avatar | varchar(256) | 头像 |
| role | varchar(16) | CUSTOMER / ADMIN |
| create_time | datetime | - |

### 5.7 inv_stock_log（库存变更流水表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | 自增 |
| dish_id | bigint | 菜品ID |
| change_type | varchar(16) | ORDER=下单 / ADD_DISH=加菜 / CANCEL=撤销回滚 / MANUAL=手动调整 |
| change_qty | int | 变更数量（正数增加、负数减少） |
| before_qty | int | 变更前库存 |
| after_qty | int | 变更后库存 |
| order_no | varchar(32) | 关联订单号 |
| create_time | datetime | - |

### 5.8 ai_prediction_record（AI备菜预测记录表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | 自增 |
| predict_date | date | 预测目标日期 |
| dish_id | bigint | 菜品ID |
| base_quantity | int | 时序基础预测 |
| ai_suggest_quantity | int | AI建议量 |
| final_quantity | int | 人工确认量 |
| reasoning | text | AI推理过程 |
| confidence | decimal(3,2) | AI置信度（<0.4低置信度） |
| recent_avg_score | decimal(2,1) | 近30天评分均值 |
| status | tinyint | 0=待确认 / 1=已确认 |
| confirmed_by | bigint | 确认人ID |
| create_time | datetime | - |

### 5.9 ai_knowledge_document（RAG文档元数据表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK | 自增 |
| file_name | varchar(128) | 原始文件名 |
| file_url | varchar(256) | 文件路径 |
| chunk_count | int | 分块数 |
| version | int | 版本号 |
| status | varchar(16) | draft / active / archived |
| effective_from | datetime | 生效时间 |
| create_time | datetime | - |

## 6. 订单状态机

```
ORDERED(0)
  ├── 厨房点[完成] → SERVED(10)
  │     ├── 顾客[确认结账] → PAID(20)
  │     ├── 顾客[加菜] → ORDERED(0)（回退）
  │     └── 管理员[撤销] → CANCELLED(90)
  ├── 顾客[确认结账] → PAID(20)
  └── 管理员[撤销] → CANCELLED(90)
```

**状态变更规则**：所有状态变更 SQL 必须带旧状态条件（乐观锁）：
`UPDATE oms_order SET status=? WHERE id=? AND status=?`

## 7. 接口清单

> 标注 `[DONE]` 为已实现，未标注为待实现。
> 所有接口前缀 `/api/**` 需 JWT 鉴权（Header: `Authorization: Bearer <token>`），放行路径见 7.1 标注。

### 7.1 用户认证 `[DONE]`

| 方法 | 路径 | 入参 | 出参 data | 说明 | 鉴权 |
|------|------|------|------|------|:--:|
| POST | /api/auth/wx-login | `{ code }` | `{ token, userId, role, needRegister }` | C端微信登录；新用户返回 needRegister=true | **放行** |
| POST | /api/auth/register | `{ code, nickname, avatar, phone }` | `{ token, userId, role }` | C端新用户注册 | **放行** |
| POST | /api/auth/login | `{ username, password }` | `{ token, userId, role }` | B端管理员及PC测试登录 | **放行** |

### 7.2 选座 `[DONE]`

| 方法 | 路径 | 入参 | 出参 data | 说明 | 鉴权 |
|------|------|------|------|------|:--:|
| GET | /api/seat/available | — | `[{ seatNumber, occupied }]` | 全量座位及占用状态（A01-A05, B01-B05） | **放行** |

### 7.3 分类管理 `[DONE]`

| 方法 | 路径 | 入参 | 出参 data | 说明 | 鉴权 |
|------|------|------|------|------|:--:|
| GET | /api/admin/dish/category/list | — | `[{ id, name, sort, createTime }]` | 全部分类（按sort升序） | ADMIN |
| GET | /api/admin/dish/category/{id} | Path: id | `{ id, name, sort, createTime }` | 分类详情 | ADMIN |
| POST | /api/admin/dish/category/add | `{ name, sort }` | — | 新增分类 | ADMIN |
| PUT | /api/admin/dish/category/update | `{ id, name, sort }` | — | 更新分类 | ADMIN |
| DELETE | /api/admin/dish/category/delete/{id} | Path: id | — | 删除分类 | ADMIN |

### 7.4 菜品浏览

> 顾客端查看菜品列表与详情。

| 方法 | 路径 | 入参 | 出参 data | 说明 | 鉴权 |
|------|------|------|------|------|:--:|
| GET | /api/dish/list | Query: `?categoryId`（可选） | `[{ id, name, categoryId, categoryName, price, image, status, dailyStock, ingredients }]` | 按分类查询起售菜品 `[DONE]` | CUSTOMER |
| GET | /api/dish/detail/{id} | Path: id | `{ id, name, categoryId, categoryName, price, image, status, dailyStock, ingredients, reviews: [{ score, comment, createTime }] }` | 菜品详情（含已有评价），供小程序菜品详情页使用 | CUSTOMER |

### 7.5 顾客端订单

| 方法 | 路径 | 入参 | 出参 data | 说明 | 鉴权 |
|------|------|------|------|------|:--:|
| POST | /api/order/submit | `{ seatNumber, items: [{ dishId, quantity, remark }] }` | `{ orderId, orderNo, status:0 }` | 下单（Redis+Lua原子预扣库存） `[DONE]` | CUSTOMER |
| POST | /api/order/{id}/add-dish | `{ items: [{ dishId, quantity }] }` | `{ orderId, addedDetails }` | 加菜（SERVED→ORDERED回退） | CUSTOMER |
| POST | /api/order/{id}/pay | — | `{ orderId, status:20 }` | 确认结账（Mock支付，生成UUID流水号） `[DONE]` | CUSTOMER |
| GET | /api/order/my-list | Query: `?page&size` | `{ total, records: [{ orderId, orderNo, seatNumber, totalAmount, status, createTime }] }` | 我的历史订单 | CUSTOMER |
| GET | /api/order/my-detail/{id} | Path: id | `{ id, orderNo, seatNumber, totalAmount, status, availableActions, details, createTime }` | 订单详情含可用按钮（availableActions 根据当前状态返回：ADD_DISH/PAY/REVIEW） | CUSTOMER |

### 7.6 管理端订单

| 方法 | 路径 | 入参 | 出参 data | 说明 | 鉴权 |
|------|------|------|------|------|:--:|
| GET | /api/order/admin-list | Query: `?status&seatNumber&page&size` | `{ total, records: [{ id, orderNo, seatNumber, dishSummary, totalAmount, status, createTime }] }` | 全部订单（支持筛选） | ADMIN |
| POST | /api/order/{id}/cancel | `{ cancelReason }` | `{ orderId, status:90 }` | 撤销订单（乐观锁+库存回滚） `[DONE]` | ADMIN |
| POST | /api/order/{id}/complete | — | `{ orderId, status:10 }` | 厨房完成出餐（由 KitchenBoardController 中的 `/serve` 统一实现） | ADMIN |

### 7.7 厨房看板 `[DONE]`

| 方法 | 路径 | 入参 | 出参 data | 说明 | 鉴权 |
|------|------|------|------|------|:--:|
| GET | /api/kitchen-board/orders | — | `[{ orderId, seatNumber, dishList: [{ dishName, quantity, remark }], createTime }]` | ORDERED订单HTTP快照（断线重连用） | ADMIN |
| — | WebSocket /ws/kitchen-board | — | `{ event_type, order_id, seat_number, dish_list, create_time }` | 实时推送，握手阶段通过 `?token=xxx` 参数鉴权 | 连接时传token |

**WebSocket 事件模型**：
```json
{
  "event_type": "order_created | order_updated | order_paid | order_cancelled",
  "order_id": 123,
  "order_version": 2,
  "seat_number": "A05",
  "dish_list": [
    {"dish_name": "水煮鱼", "quantity": 1, "remark": "加辣"}
  ],
  "create_time": "2026-07-14T12:08:30+08:00"
}
```

### 7.8 菜品管理 `[DONE]`

| 方法 | 路径 | 入参 | 出参 data | 说明 | 鉴权 |
|------|------|------|------|------|:--:|
| POST | /api/admin/dish/create | `{ name, categoryId, price, image, dailyStock, alertThreshold, ingredients }` | `{ dishId }` | 新增菜品（image 为 OSS URL） | ADMIN |
| PUT | /api/admin/dish/update/{id} | Path: id + 同create（部分可选） | — | 更新菜品 | ADMIN |
| DELETE | /api/admin/dish/delete/{id} | Path: id | — | 删除菜品 | ADMIN |
| GET | /api/admin/dish/detail/{id} | Path: id | `{ id, name, categoryId, price, image, status, dailyStock, alertThreshold, ingredients }` | 菜品详情 | ADMIN |
| GET | /api/admin/upload/sts-token | — | `{ accessKeyId, accessKeySecret, securityToken, expiration, bucket, region, endpoint }` | 阿里云 OSS STS 临时凭证（15min过期），前端凭此直传图片到 OSS | ADMIN |

> **图片上传流程**：前端调 STS Token 接口获取临时凭证 → el-upload 直传 OSS → 即时回显 → 提交菜品时将 OSS URL 作为 `image` 字段存入 `pms_dish.image`。C 端通过该 URL 读取展示菜品图片。

### 7.9 库存管理 `[DONE]`

| 方法 | 路径 | 入参 | 出参 data | 说明 | 鉴权 |
|------|------|------|------|------|:--:|
| GET | /api/admin/stock/view/{dishId} | Path: dishId | `{ dishId, dishName, dailyStock, alertThreshold, logs: [{ changeType, changeQty, beforeQty, afterQty, orderNo, createTime }] }` | 库存及变更流水（需增强返回结构，补充 dishName/dailyStock/alertThreshold） | ADMIN |
| PUT | /api/admin/stock/update/{dishId} | Path: dishId + `{ changeQty, changeType }` | `{ dishId, beforeQty, afterQty }` | 手动调整库存 | ADMIN |

### 7.10 管理端评价列表

| 方法 | 路径 | 入参 | 出参 data | 说明 | 鉴权 |
|------|------|------|------|------|:--:|
| GET | /api/admin/review/list | Query: `?score`（可选筛选） | `[{ id, orderId, orderNo, userId, score, comment, createTime }]` | 管理端评价列表，支持按评分筛选 | ADMIN |

### 7.11 顾客端餐后评价

| 方法 | 路径 | 入参 | 出参 data | 说明 | 鉴权 |
|------|------|------|------|------|:--:|
| POST | /api/review/submit | `{ orderId, score, comment }` | `{ reviewId }` | PAID状态订单可评价（一单一评） `[DONE]` | CUSTOMER |

### 7.12 管理端仪表盘

| 方法 | 路径 | 入参 | 出参 data | 说明 | 鉴权 |
|------|------|------|------|------|:--:|
| GET | /api/admin/dashboard/stats | — | `{ todayOrderCount, todayRevenue, pendingServeCount, lowStockDishCount }` | 仪表盘首页统计卡片数据 | ADMIN |

### 7.13 AI知识库 `[DONE]`

| 方法 | 路径 | 入参 | 出参 data | 说明 | 鉴权 |
|------|------|------|------|------|:--:|
| POST | /api/admin/knowledge/upload | Multipart: file | `{ documentId, fileName, chunkCount }` | 上传文档 → Java调Python `/ai/knowledge/process` 向量化 → 存入Milvus | ADMIN |

### 7.14 Python 端—AI智能客服

| 方法 | 路径 | 入参 | 出参 | 说明 |
|------|------|------|------|------|
| POST | /ai/chat | `{ userId, question, conversationHistory }` | SSE流: `event: message
data: { answer, sources }` | 单Agent + Function Calling，调用RAG检索菜品知识，流式返回答案 |

### 7.15 Python 端—AI备菜预测（LangGraph）

| 方法 | 路径 | 入参 | 出参 data | 说明 |
|------|------|------|------|------|
| POST | /ai/predict/trigger | `{ targetDate }` | `{ taskId, status: "running" }` | 触发LangGraph预测流程：supervisor→数据节点并行→时序预测→LLM修正→落库 |
| GET | /ai/predict/result | Query: `?date=2026-07-18` | `[{ dishId, dishName, baseQuantity, aiSuggestQuantity, finalQuantity, reasoning, confidence, recentAvgScore, status }]` | 查询指定日期预测结果；confidence<0.4标记低置信度 |
| PUT | /ai/predict/confirm | `{ predictDate, dishId, finalQuantity, confirmedBy }` | `{ status: "confirmed" }` | 管理员确认/覆盖预测数量 |

### 7.16 Python 端—AI知识库处理

| 方法 | 路径 | 入参 | 出参 data | 说明 |
|------|------|------|------|------|
| POST | /ai/knowledge/process | `{ documentId, fileUrl, fileName }` | `{ chunkCount, version }` | 下载文件→RecursiveCharacterTextSplitter分块→Embedding→存入Milvus |
| POST | /ai/knowledge/search | `{ query, topK=3 }` | `[{ content, score, documentName }]` | Milvus向量检索，返回Top-K相关文档片段，供Agent Function Calling调用 |

> Java 通过 RestTemplate HTTP POST JSON 调用上述 Python 接口，Python 服务地址由 `application.yml` 中 `python-service.url` 配置。

## 8. 跨语言调用与AI服务架构

### 8.1 Java ↔ Python 通信

- 协议：HTTP POST JSON（Java 通过 RestTemplate 调用 Python FastAPI）
- Python 服务地址由 `application.yml` 中 `python-service.url` 配置
- Java 仅在 `/api/admin/knowledge/upload` 和 AI客服代理中调用 Python 接口

### 8.2 LangGraph 备菜预测节点图（Supervisor 架构）

```
Supervisor Graph
  │
  ├── supervisor_node ── 条件路由（根据 task_type）
  │     ├── "predict" → predict_subgraph
  │     ├── "other"  → other_node（兜底，返回不支持提示）
  │     └── 空/null  → END（返回无法识别）
  │
  ├── predict_subgraph（备菜预测子图）
        │
        ▼
      [get_sales_30d] ── 拉取菜品30天日销量 → MySQL
        │
        ▼
      [get_tomorrow_weather] ── MCP 调用天气 API
        │
        ▼
      [get_holiday_info] ── MCP 判断节假日
        │
        ▼
      [get_recent_reviews] ── 拉取菜品30天评分均值 → MySQL
        │
        ▼
      [time_series_predict] ── 30天销量统计（均值/中位数/近7日均值）→ 数据不足时冷启动降级（同类菜品→新品库存→日常库存→兜底）
        │
        ▼
      [llm_adjust] ── Prompt → 大模型修正 → 输出 JSON
        │
        ▼
      [save_result] ── 写入 ai_prediction_record 表
```

**架构说明**：Supervisor 多智能体入口根据 task_type 路由：已知类型分发到对应子图，未知类型走 other_node 兜底返回不支持提示，空/null 直接结束。备菜预测子图由 Supervisor 统一调度。LLM Prompt 外置到 `app/prompts/predict_llm_prompt.txt`，Supervisor 路由提示词外置到 `app/prompts/supervisor_prompt.txt`。整个图中仅 llm_adjust 一个节点调用 LLM，其余为确定性 Python 函数。天气和节假日数据来源：
  - **天气**：`weather_tools.py` → OpenWeatherMap `/data/2.5/forecast` REST API；城市写死为 `WEATHER_CITY` 环境变量，非 MCP 直调 HTTP
  - **节假日**：`holiday_tools.py` → ModelScope `china-festival-mcp` 通过标准 MCP JSON-RPC 协议（initialize → tools/list → tools/call）查询
### 8.3 AI客服架构（单Agent + Function Calling）

- Agent 挂载两个工具函数：`search_menu(query)` 检索菜品信息、`search_knowledge(query)` RAG 检索知识库
- 问答结果通过 Redis 缓存（全量热点缓存），降低 LLM 调用成本
- 前端以 SSE（Server-Sent Events）流式接收回答

## 9. 核心机制设计

### 9.1 JWT 鉴权

1. JWT Payload 包含 `userId` 和 `role`。
2. `JwtInterceptor` 拦截所有 `/api/**` 请求，从 `Authorization: Bearer <token>` 中解析。
3. 解析成功后将 `userId` 和 `role` 存入 `UserContext`（基于 ThreadLocal），Controller 和 Service 通过 `UserContext.getUserId()` 直接获取当前用户，无需层层传参。请求结束后由 `JwtInterceptor.afterCompletion()` 清理 ThreadLocal，防止内存泄漏。
4. 放行路径：`/api/auth/**`、`/api/seat/**`。
5. Token 过期时间由 `application.yml` 中的 `jwt.expiration` 配置（默认 7200 秒）。

### 9.2 库存扣减（Redis + Lua）

```
下单时执行 Lua 脚本（伪代码）：
for each (dishId, quantity) in items:
    stock = redis.get("dish:stock:" + dishId)
    if stock < quantity: return FAIL
for each (dishId, quantity) in items:
    redis.decr("dish:stock:" + dishId, quantity)
return SUCCESS
```

- Redis 扣减成功后才写入 MySQL。
- MySQL 写入时附带状态条件（乐观锁）作为二次保障。

### 9.3 WebSocket 实时推送

- 连接端点：`/ws/kitchen-board`。
- 握手时通过 URL 参数携带 JWT Token 完成鉴权。
- 推送时机：`OrderService` 中状态变更（下单、结账、撤销、完成出餐）时调用 `KitchenBoardWebSocketHandler.sendMessage()`。
- 断线重连：前端重连后调 `GET /api/kitchen-board/orders` 拉取 HTTP 快照补齐。

### 9.4 Mock 支付

- `POST /api/order/{id}/pay` 不调任何第三方支付 API。
- 接口内部：乐观锁更新 `status = 20` → 记录 `pay_time = now()` → `payment_trade_no = UUID` → WebSocket 推送 `order_paid`。
- 后续如需接入微信支付：抽取 `PaymentStrategy` 接口，新增 `WechatPaymentStrategyImpl` 即可。

## 10. 环境配置

`application.yml` 关键配置项：

| 配置项 | 说明 | 默认值 |
|------|------|------|
| spring.datasource.* | MySQL 连接信息 | 本地 3306 / smart_kitchen |
| spring.data.redis.* | Redis 连接信息 | localhost:6379 |
| jwt.secret | JWT 签名密钥 | Base64 编码的随机串 |
| jwt.expiration | Token 有效期（秒） | 7200 |

## 11. RabbitMQ 订单超时延迟消息

### 11.1 方案选型

| 方案 | 可靠性 | 延迟精度 | 运维复杂度 | 结论 |
|------|--------|----------|------------|------|
| Redis Keyspace Notification | 低（`__keyevent` 通知不可靠，可能丢失） | 一般 | 低 | 仅适用于轻量场景 |
| Spring `@Scheduled` 定时轮询 DB | 中等 | 差（依赖轮询间隔） | 低 | 大规模订单时 DB 压力大 |
| RabbitMQ DLX + TTL | 高（消息持久化 + ACK 确认） | 高（毫秒级） | 中等 | **选用**，无需额外插件 |
| RabbitMQ Delayed Message Plugin | 高 | 高 | 需额外安装插件 | 更简洁但依赖插件 |

**结论**：选用 RabbitMQ 死信队列（DLX）+ TTL 方案，无需安装额外插件，通用性强。

### 11.2 架构设计

```
submitOrder()
     │
     ▼
发送消息（orderId）到 order.delay.exchange
     │
     ▼
order.delay.queue（x-message-ttl=30min，绑定死信交换机）
     │  ─── 30 分钟后消息过期 ───
     ▼
order.timeout.exchange（死信交换机 DLX，重新路由过期消息）
     │
     ▼
order.timeout.queue（消费者实际监听）
     │
     ▼
OrderTimeoutConsumer.handleOrderTimeout(orderId)
     │
     ├─ 查订单状态
     │    ├─ ORDERED / SERVED（未支付） → cancelOrder() 取消 + 归还库存
     │    └─ PAID / CANCELLED → 跳过
     └─ 手动 ACK
```

### 11.3 交换机与队列设计

| 组件 | 名称 | 类型 | 关键参数 |
|------|------|------|----------|
| 延迟交换机 | `order.delay.exchange` | Direct | — |
| 延迟队列 | `order.delay.queue` | Durable | `x-message-ttl=1800000`，`x-dead-letter-exchange=order.timeout.exchange`，`x-dead-letter-routing-key=order.timeout` |
| 死信交换机 | `order.timeout.exchange` | Direct | — |
| 超时消费队列 | `order.timeout.queue` | Durable | 消费者手动 ACK |
| 绑定 1 | delayBinding | — | `order.delay.exchange` → `order.delay.queue`，routingKey=`order.delay` |
| 绑定 2 | timeoutBinding | — | `order.timeout.exchange` → `order.timeout.queue`，routingKey=`order.timeout` |

### 11.4 文件变更清单

1. **新增 `config/RabbitMQConfig.java`**：`@Configuration` + `@Bean` 声明交换机、队列、绑定，队列持久化，常量 `public static final` 向外暴露。

2. **新增 `service/OrderTimeoutConsumer.java`**：`@RabbitListener(queues = "order.timeout.queue")` 消费超时消息。查订单状态 → 仅 ORDERED/SERVED 调用 `orderService.cancelOrder()` → PAID/CANCELLED 直接 ACK。消费异常记录日志后 ACK（避免死循环），配合人工补偿。

3. **修改 `service/impl/OrderServiceImpl.java`**：注入 `RabbitTemplate`，`submitOrder()` 落库成功后调用 `rabbitTemplate.convertAndSend(ORDER_DELAY_EXCHANGE, ORDER_DELAY_ROUTING_KEY, order.getId().toString())`，消息体仅含 `orderId`。

### 11.5 边界情况

| 场景 | 处理方式 |
|------|----------|
| 用户在 30 分钟内主动支付 | 消费者收到时订单已为 PAID → 跳过 |
| 用户/管理员在 30 分钟内主动撤销 | 消费者收到时已为 CANCELLED → 跳过 |
| 超时消息消费时订单已不存在 | `orderService.getById()` 返回 null → 记录日志后 ACK |
| RabbitMQ 宕机期间下单 | 消息未发送成功，订单正常流转但不自动取消，需人工排查 |
| 消费者处理异常 | 记录日志后手动 ACK，配合告警后续人工补偿 |
| 支付与超时消费并发 | `cancelOrder()` 已有状态校验（仅 ORDERED/SERVED），支付先到则跳过超时，超时先到则支付校验失败 |

### 11.6 application.yml 已有配置

```yaml
spring:
  rabbitmq:
    host: ${MQ_HOST:localhost}
    port: ${MQ_PORT:5672}
    username: ${MQ_USER:guest}
    password: ${MQ_PWD:guest}
    virtual-host: /
    publisher-confirm-type: correlated   # 消息投递到交换机确认
    publisher-returns: true              # 消息无法路由到队列时回调
```

### 11.7 订单状态机（更新）

```
ORDERED(0)
  ├── 厨房点[完成] → SERVED(10)
  │     ├── 顾客[确认结账] → PAID(20)
  │     ├── 顾客[加菜] → ORDERED(0)（回退）
  │     └── 管理员[撤销] → CANCELLED(90)
  ├── 顾客[确认结账] → PAID(20)
  ├── 管理员[撤销] → CANCELLED(90)
  └── RabbitMQ超时30min → CANCELLED(90)  ← 新增
```
