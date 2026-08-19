# 智慧后厨备菜与点单系统 技术规格说明书

> 版本：v5.0（按已交付实现重写）
> 本文档描述系统的技术架构、工程规范、数据模型、接口清单与核心机制实现，是开发与维护的技术依据。产品业务描述见 PRD，迭代进度见 plan.md。

---

## 1. 系统架构

### 1.1 总体架构

```text
┌──────────────────────┐          ┌──────────────────────┐
│  C端 微信小程序        │          │  B端 PC Web (admin-web) │
│  (原生 WXML/WXSS/JS)  │          │  Vue3 + Element Plus  │
└─────────┬────────────┘          └─────────┬────────────┘
          │ HTTP / WebSocket                 │ HTTP / WebSocket
          ▼                                  ▼
┌─────────────────────────────────────────────────────────┐
│           Java 核心服务 smart-kitchen (Spring Boot)       │
│  JWT 拦截器 / 全局异常处理 / Controller-Service-Mapper     │
│  WebSocket Handler（厨房看板 + 顾客通知）                   │
│  RabbitMQ 生产者/消费者（订单超时）                         │
└───┬────────┬────────┬────────┬───────────────┬──────────┘
    │        │        │        │               │ HTTP (RestTemplate)
    ▼        ▼        ▼        ▼               ▼
┌───────┐ ┌─────┐ ┌───────┐ ┌────────┐ ┌──────────────────────────┐
│ MySQL │ │Redis│ │RabbitMQ│ │阿里云OSS│ │ Python AI 服务 smart-      │
│  8.0  │ │缓存/ │ │ DLX+TTL│ │图片存储 │ │ kitchen-ai (FastAPI)      │
│ 业务表 │ │库存 │ │ 超时取消│ │        │ │  ├─ AI客服 ReAct Agent     │
└───▲───┘ └──▲──┘ └───────┘ └────────┘ │  ├─ LangGraph 备菜预测     │
    │        │                          │  └─ RAG 知识库            │
    │        │                          └──┬───────┬───────┬───────┘
    │ PyMySQL│ (销量/评价/落库)              │       │       │
    └────────┴──────────────────────────────┘       │       │
    │  HTTP（库存/配料代理查询）                       │       │
    └────────────────────────────────────────────────┘       │
                                          ┌──────────────────┤
                                          ▼                  ▼
                                   ┌────────────┐    ┌──────────────┐
                                   │ Milvus Lite │    │ 外部服务      │
                                   │ 向量库(本地) │    │ DeepSeek LLM │
                                   └────────────┘    │ 百炼 Embedding│
                                                      │ OpenWeatherMap│
                                                      │ 节假日 MCP    │
                                                      └──────────────┘
```

### 1.2 模块职责边界

| 模块 | 职责 | 明确不做 |
|------|------|----------|
| Java 后端 | 全部业务交易链路：鉴权、菜品、订单、库存、评价、看板推送、MQ 消息；作为 B 端访问 AI 能力的代理 | 不做 AI 推理与向量计算 |
| Python AI 服务 | AI 客服 Agent、备菜预测工作流、知识库向量化与检索 | 不承接 C/B 端直接业务请求（客服 SSE 除外） |
| admin-web | B 端全部管理界面 | 不直连数据库/AI 服务 |
| miniprogram | C 端点单界面；AI 客服直连 Python（SSE） | 业务交易一律走 Java |

**跨语言调用协议**：Java ↔ Python 之间为 HTTP POST JSON。Java 侧通过 `RestTemplate` 调用，Python 服务地址由配置项 `smart-kitchen.python-service.url` 指定。Python 客服工具查询库存/配料时回调 Java 代理接口（`/api/proxy/dish/**`），保证数据层归属统一。

---

## 2. 技术栈与版本

### 2.1 Java 后端（smart-kitchen）

| 领域 | 选型 | 版本 |
|------|------|------|
| 运行时 | Java | 21 |
| 框架 | Spring Boot | 3.2.0 |
| 持久层 | MyBatis-Plus | 3.5.5 |
| 数据库 | MySQL（mysql-connector-j） | 8.x |
| 缓存 | Spring Data Redis（Lettuce） | 随 Boot |
| 消息队列 | Spring AMQP（RabbitMQ） | 随 Boot |
| 实时通信 | Spring WebSocket | 随 Boot |
| 认证 | JJWT | 0.11.5 |
| 对象存储 | 阿里云 OSS SDK | 3.17.4 |
| 文件解析 | Apache POI（Word）/ PDFBox（PDF） | 3.16 / 2.0.30 |
| 其他 | Lombok、dotenv-java、Validation | — |
| 测试 | spring-boot-starter-test + H2 | — |

### 2.2 Python AI 服务（smart-kitchen-ai）

| 领域 | 选型 |
|------|------|
| Web 框架 | FastAPI + Uvicorn |
| Agent / 工作流 | LangGraph（`create_react_agent` / `StateGraph`）+ langchain-openai |
| LLM | DeepSeek（OpenAI 兼容接口，流式） |
| Embedding | 阿里云百炼（DashScope 兼容接口），向量维度 1024 |
| 向量库 | Milvus Lite（本地文件 `data/milvus.db`） |
| 文本分块 | langchain-text-splitters `RecursiveCharacterTextSplitter` |
| 存储 | PyMySQL（MySQL）、redis-py（Redis） |
| HTTP | httpx / requests |
| 定时任务 | APScheduler（AsyncIOScheduler，Cron） |

### 2.3 前端

| 端 | 选型 |
|----|------|
| B 端 admin-web | Vue 3.5 + Vite 8 + Element Plus 2.14 + Pinia 4 + Vue Router 4 + Axios |
| C 端 miniprogram | 微信原生小程序（WXML/WXSS/JS，基础库 3.3.4） |

---

## 3. 工程结构

### 3.1 smart-kitchen（Java）

```text
src/main/java/com/smartkitchen/
├── SmartKitchenApplication.java
├── common/          # Result<T> 统一响应 + 枚举（OrderStatusEnum/StockChangeTypeEnum 等）
├── config/          # JwtInterceptor、JwtUtil、UserContext、WebMvcConfig、WebSocketConfig、
│                    # WebSocketAuthInterceptor（握手 JWT 鉴权）、
│                    # KitchenBoardWebSocketHandler、CustomerWebSocketHandler、RabbitMQConfig、
│                    # MybatisPlusConfig、RestTemplateConfig、DotenvLoader
├── consumer/        # OrderTimeoutConsumer（MQ 超时消费）
├── controller/      # 15 个 REST 控制器（含 ai/AdminPredictController）
├── dto/             # 请求 DTO / 响应 VO（20 个）
├── entity/          # 9 个数据库实体
├── exception/       # GlobalExceptionHandler
├── mapper/          # 9 个 MyBatis Mapper
└── service/         # 12 个业务接口
    └── impl/        # 12 个业务实现
src/main/resources/
├── scripts/deduct_stock.lua   # 库存原子扣减脚本
├── scripts/return_stock.lua   # 库存回滚脚本
├── mapper/*.xml               # 自定义 SQL（菜品库存扣减等）
└── db/schema.sql              # 全量建表 DDL
src/test/                      # 17 个测试类（H2 内存库，schema-h2.sql / data-h2.sql）
```

### 3.2 smart-kitchen-ai（Python）

```text
app/
├── main.py            # FastAPI 入口 + APScheduler 生命周期
├── config.py          # Settings（环境变量）
├── agents/
│   ├── cs_agent.py          # AI 客服 ReAct Agent
│   └── predict_agent.py     # 备菜预测 StateGraph（节点与构图同文件）
├── api/
│   ├── chat.py              # /ai/chat
│   ├── knowledge.py         # /ai/knowledge/*
│   └── predict.py           # /ai/predict/*
├── db/                # mysql_client / redis_client / milvus_client
├── models/schemas.py  # Pydantic 模型
├── prompts/           # cs_agent_prompt.txt / predict_llm_prompt.txt（Prompt 外置）
├── services/          # llm_service（SSE）/ semantic_cache_service（语义缓存）/ predict_service / rag_service
├── tools/             # dish_tools / predict_tools / weather_tools / holiday_tools
└── utils/mcp_client.py      # 外部工具路由分发
tests/                 # test_mcp_tools.py / test_predict.py / test_semantic_cache.py
data/milvus.db/        # Milvus Lite 数据文件
```

### 3.3 admin-web（Vue 3）

```text
src/
├── router/index.js        # 路由 + 登录/角色守卫
├── stores/auth.js         # Pinia 登录态
├── utils/
│   ├── request.js         # Axios 封装（拦截器）
│   ├── websocket.js       # WS 客户端（心跳/重连/快照回调）
│   └── oss.js             # 图片上传（经后端代理）
└── views/                 # layout / login / dashboard / kitchen-board / orders
                           # / dishes / stock / predict / knowledge / review
```

### 3.4 miniprogram（微信小程序）

```text
├── app.js / app.json      # 登录流程、globalData、tabBar（菜单/订单/AI客服）
├── utils/
│   ├── request.js         # wx.request Promise 封装（401 重登）
│   ├── sse.js             # enableChunked 流式接收
│   └── cart.js            # 购物车本地存储（7 个方法）
└── pages/                 # menu / dish-detail / order / order-detail
                           # / my-orders / review / ai-chat / register
```

---

## 4. 开发规范

1. **分层调用**：Controller → Service → Mapper，禁止跨层调用。
   - Controller：接收请求、参数校验、调用 Service、包装 `Result` 返回；不含业务逻辑，不做 DTO↔Entity 转换，不构建 `QueryWrapper`。
   - Service/ServiceImpl：全部业务逻辑；DTO↔Entity 转换；`QueryWrapper` 构建。
   - Mapper：纯 SQL 交互。
2. **统一响应体**：`Result<T>`，格式 `{ code, message, data }`；成功 `code=200`。
3. **异常处理**：业务异常 `throw new RuntimeException("msg")`，由 `GlobalExceptionHandler` 统一捕获转换为错误响应。
4. **命名**：Controller 方法名反映动作（`list` / `add` / `update` / `delete` / `submit` / `pay`）。
5. **注释**：public 方法带 Javadoc。
6. **配置**：密钥一律环境变量注入（`.env` 不提交版本库）。

---

## 5. 数据模型

数据库 `smart_kitchen`（utf8mb4），共 9 张表。DDL 以 `smart-kitchen/src/main/resources/db/schema.sql` 为准。

### 5.1 sys_user（系统用户表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK AI | 自增 |
| openid | varchar(64) UNIQUE | 微信 openid（C 端登录凭证） |
| username | varchar(32) UNIQUE | 账号（B 端管理员使用） |
| password | varchar(128) | 密码（B 端管理员使用） |
| phone | varchar(16) | 手机号 |
| nickname | varchar(32) | 昵称 |
| avatar | varchar(256) | 头像 |
| role | varchar(16) | CUSTOMER / ADMIN，默认 CUSTOMER |
| create_time | datetime | 默认 CURRENT_TIMESTAMP |

### 5.2 pms_category（菜品分类表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK AI | - |
| name | varchar(32) | 分类名 |
| sort | int | 排序值，默认 0 |
| create_time / update_time | datetime | - |

### 5.3 pms_dish（菜品表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK AI | - |
| name | varchar(64) | 菜品名称 |
| category_id | bigint | 分类 ID |
| price | decimal(10,2) | 售价 |
| image | varchar(256) | 图片 URL（OSS） |
| status | tinyint | 1=起售 / 0=停售，默认 1 |
| daily_stock | int | 每日库存 |
| alert_threshold | int | 库存预警阈值 |
| ingredients | text | 配料（JSON 数组） |
| allergens | text | 过敏原信息 |
| new_product_initial_stock | int | 新品初始库存（AI 预测冷启动基准） |
| create_time / update_time | datetime | - |

### 5.4 oms_order（订单主表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK AI | - |
| order_no | varchar(32) UNIQUE | 订单编号（UUID 去横线截 16 位） |
| user_id | bigint | 顾客 ID |
| seat_number | varchar(8) | 座位号 |
| total_amount | decimal(10,2) | 本单金额（不含子订单） |
| status | tinyint | 0=ORDERED / 10=SERVED / 20=PAID / 90=CANCELLED |
| cancel_reason | varchar(16) | 撤销原因 |
| pay_time | datetime | 支付登记时间 |
| payment_trade_no | varchar(64) UNIQUE | 支付流水号（模拟：`SIM_`+时间戳，子订单追加 `_N`） |
| complete_time | datetime | 出餐完成时间 |
| operator_id | bigint | 最后操作管理员 |
| remark | varchar(256) | 备注 |
| parent_order_id | bigint | 父订单 ID（加菜子订单指向原订单，主订单为 NULL） |
| create_time / update_time | datetime | - |

### 5.5 oms_order_detail（订单明细表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK AI | - |
| order_id | bigint KEY | 所属订单（父或子订单） |
| dish_id | bigint | 菜品 ID |
| dish_name | varchar(64) | 菜名快照（下架菜品追加「（已下架）」标记） |
| quantity | int | 数量 |
| price | decimal(10,2) | 下单时快照价格（下架菜品记 0） |
| is_added | tinyint | 0=首单 / 1=加菜 |
| create_time | datetime | - |

### 5.6 oms_review（评价表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK AI | - |
| order_id | bigint UNIQUE | 订单 ID（一单一评） |
| user_id | bigint | 评价人 |
| score | tinyint | 1–5 星 |
| comment | varchar(512) | 文字评价（可选） |
| create_time | datetime | - |

### 5.7 inv_stock_log（库存变更流水表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK AI | - |
| dish_id | bigint KEY | 菜品 ID |
| change_type | varchar(16) | RESERVE 预扣 / DEDUCT 扣减 / ROLLBACK 回滚 / MANUAL 人工调整 |
| change_qty | int | 变更数量（正增负减） |
| before_qty / after_qty | int | 变更前/后库存 |
| order_no | varchar(32) | 关联订单号 |
| create_time | datetime | - |

> 当前实现中，流水在管理员手动调整库存时写入（MANUAL）。

### 5.8 ai_prediction_record（AI 备菜预测记录表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK AI | - |
| predict_date | date | 预测目标日期，与 dish_id 组成唯一键 `uk_date_dish` |
| dish_id | bigint | 菜品 ID |
| base_quantity | int | 时序基础预测量 |
| ai_suggest_quantity | int | AI 建议量 |
| final_quantity | int | 人工确认量 |
| reasoning | text | AI 推理说明 |
| confidence | decimal(3,2) | 置信度（<0.4 标记低置信度） |
| recent_avg_score | decimal(2,1) | 近期评分均值 |
| status | tinyint | 0=待确认 / 1=已确认 |
| confirmed_by | bigint | 确认人 ID |
| create_time | datetime | - |

### 5.9 ai_knowledge_document（RAG 文档元数据表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | bigint PK AI | - |
| title | varchar(128) | 文档标题 |
| chunk_count | int | 分块数 |
| version | varchar(20) | 版本号，默认 '1.0' |
| status | varchar(16) | draft / active / archived，默认 draft |
| effective_from | datetime | 生效时间 |
| create_time | datetime | - |

---

## 6. 订单状态机与父子订单规则

### 6.1 状态机

```
ORDERED(0)
  ├── 厨房[完成出餐] → SERVED(10)        （若已登记支付 → 直接 PAID(20)）
  │     ├── 顾客[结账] → PAID(20)
  │     ├── 顾客[加菜] → 新建 ORDERED 子订单（父单状态不变）
  │     └── 管理员[撤销] / MQ超时 → CANCELLED(90)
  ├── 顾客[结账] → 登记 pay_time + 流水号，状态保持 ORDERED
  ├── 管理员[撤销] / MQ超时 → CANCELLED(90)
  └── PAID / CANCELLED 为终态
```

**规则**：

- 状态迁移采用「应用层预校验 + SQL 条件更新最终裁决」：Service 先校验当前状态给出友好报错，真正写入用条件 UPDATE（`WHERE id=? AND status IN (...)`，支付另加 `pay_time IS NULL`），影响行数 0 即被并发抢先，报错提示——与库存扣减 `daily_stock >= ?` 同一思想，由数据库原子操作堵住先查后改窗口。
- 撤销：仅 `ORDERED` 返还库存（Redis `return_stock.lua` + MySQL 加回）；`SERVED` 不返还；级联撤销全部子订单。
- 结账幂等：`pay_time` 非空拒绝重复支付（含条件更新兜底）；`payment_trade_no` 唯一索引兜底。

### 6.2 父子订单合并规则（C 端视图）

| 维度 | 规则 |
|------|------|
| 查询范围 | 列表仅查主订单（`parent_order_id IS NULL`），再批量取出子订单合并 |
| 明细 | 父单 + 全部子订单的明细合并展示 |
| 金额 | 父单金额 + 子订单金额合计 |
| 状态 | 任一为 CANCELLED → CANCELLED；全部 PAID → PAID；全部 ≥ SERVED → SERVED；否则 ORDERED |
| 支付 | 父单结账时自动合并支付所有未支付子订单，流水号追加 `_1`、`_2` |

---

## 7. 接口清单

### 7.1 通用约定

- 统一响应：`{ code: 200, message: "...", data: ... }`。
- 鉴权：请求头 `Authorization: Bearer <token>`。
- **JWT 放行路径**：`/api/auth/**`、`/api/seat/**`、`/api/proxy/**`、`/error`。
- 分页参数：`page`（默认 1）、`size`（默认 10）；分页响应为 MyBatis-Plus `Page` 结构（`records` / `total` 等）。

### 7.2 Java 端接口

#### 认证 AuthController `/api/auth`

| 方法 | 路径 | 入参 | 响应 data | 说明 |
|------|------|------|-----------|------|
| POST | `/api/auth/wx-login` | `{ code }` | `{ token, userId, role, needRegister }` | 微信免密登录，新用户 `needRegister=true`（无 token） |
| POST | `/api/auth/register` | `{ code, nickname, avatar, phone }` | `{ token, userId, role }` | 新用户注册并绑定微信 |
| POST | `/api/auth/login` | `{ username, password }` | `{ token, userId, role }` | 账密登录（B 端管理员） |
| GET | `/api/auth/check-token` | Header token | 用户信息 | 小程序启动时校验登录态 |

#### 座位 SeatController `/api/seat`

| 方法 | 路径 | 响应 data | 说明 |
|------|------|-----------|------|
| GET | `/api/seat/available` | `[{ seatNumber, occupied }]` | 固定 10 座（A01–A05、B01–B05）全量返回；占用态由 ORDERED/SERVED 订单实时推导 |

#### 菜品浏览 DishController `/api/dish`

| 方法 | 路径 | 入参 | 响应 data | 说明 |
|------|------|------|-----------|------|
| GET | `/api/dish/list` | Query: `categoryId`（可选） | 菜品列表（含分类名、价格、图片、状态、库存、配料） | C 端菜单 |
| GET | `/api/dish/detail/{id}` | Path: id | 菜品详情（含评价列表 `reviews`） | C 端菜品详情页 |

#### 订单 OrderController `/api/order`

| 方法 | 路径 | 入参 | 响应 data | 说明 |
|------|------|------|-----------|------|
| POST | `/api/order/submit` | `{ seatNumber, remark, details: [{ dishId, quantity }] }` | `orderNo`（字符串） | 下单：Lua 扣库存 → 落库 → 推看板 → 发 MQ 延迟消息 |
| POST | `/api/order/{id}/add-dish` | `{ details: [{ dishId, quantity }] }` | — | 加菜：创建子订单 + 独立扣库存 + 推看板新卡片 |
| POST | `/api/order/{id}/pay` | — | — | 结账：登记模拟流水号，合并支付子订单 |
| GET | `/api/order/my-list` | Query: `page&size` | 分页订单（父子合并视图） | 我的历史订单 |
| GET | `/api/order/my-detail/{id}` | Path: id | 详情（合并明细/金额/状态 + `availableActions`） | C 端订单详情 |
| GET | `/api/order/admin-list` | Query: `page&size&status&seatNumber` | 分页订单 | B 端订单列表（按下单时间倒序，可筛选） |
| GET | `/api/order/admin-detail/{id}` | Path: id | 详情 + `availableActions` | B 端订单详情 |
| POST | `/api/order/{id}/cancel` | — | — | 撤销（ORDERED/SERVED → CANCELLED，级联子订单） |
| POST | `/api/order/{id}/complete` | — | — | 完成出餐（与厨房看板 serve 同一 Service 实现） |

> `availableActions` 取值：`ADD_DISH` / `PAY` / `REVIEW`。规则：ORDERED（已登记支付则不含 PAY）、SERVED → ADD_DISH + PAY；PAID 且未评价 → REVIEW；终态/已评价 → 空。

#### 厨房看板 KitchenBoardController `/api/kitchen-board`

| 方法 | 路径 | 响应 data | 说明 |
|------|------|-----------|------|
| GET | `/api/kitchen-board/orders` | `[{ ...order, details: [...] }]` | 当前 ORDERED 订单快照（时间升序），用于 WS 断线重连补齐；已下架菜品明细（price=0）被过滤 |
| POST | `/api/kitchen-board/order/{id}/serve` | — | 完成出餐（看板前端实际调用入口） |

#### 分类管理 CategoryController `/api/admin/dish/category`

| 方法 | 路径 | 入参 | 说明 |
|------|------|------|------|
| GET | `/list` | — | 全部分类（sort 升序） |
| GET | `/{id}` | Path: id | 分类详情 |
| POST | `/add` | `{ name, sort }` | 新增 |
| PUT | `/update` | `{ id, name, sort }` | 更新 |
| DELETE | `/delete/{id}` | Path: id | 删除 |

#### 菜品管理 AdminDishController `/api/admin/dish`

| 方法 | 路径 | 入参 | 说明 |
|------|------|------|------|
| GET | `/list` | Query: `categoryId`（可选） | 全量菜品列表（含停售），管理端用 |
| POST | `/create` | 菜品字段 | 新增菜品 |
| PUT | `/update/{id}` | Path: id + 菜品字段 | 更新（含上下架） |
| DELETE | `/delete/{id}` | Path: id | 删除 |
| GET | `/detail/{id}` | Path: id | 详情 |

#### 库存管理 AdminStockController `/api/admin/stock`

| 方法 | 路径 | 入参 | 响应 data | 说明 |
|------|------|------|-----------|------|
| GET | `/view/{dishId}` | Path: dishId | `{ dishId, dishName, dailyStock, alertThreshold, logs: [...] }` | 当前库存 + 变更流水 |
| PUT | `/update/{dishId}` | Path: dishId，Query: `changeQty` | — | 手动调整库存并写流水（MANUAL） |

#### 文件上传 AdminUploadController `/api/admin/upload`

| 方法 | 路径 | 入参 | 说明 |
|------|------|------|------|
| POST | `/image` | Multipart: file | 菜品图片上传，后端转存 OSS，返回图片 URL（前端实际使用） |
| GET | `/sts-token` | — | 阿里云 OSS STS 临时凭证签发（预留给前端直传模式） |

#### 仪表盘 AdminDashboardController `/api/admin/dashboard`

| 方法 | 路径 | 响应 data |
|------|------|-----------|
| GET | `/stats` | `{ todayOrderCount, todayRevenue, pendingServeCount, lowStockDishCount }` |

#### 评价

| 方法 | 路径 | 入参 | 说明 | 端 |
|------|------|------|------|----|
| POST | `/api/review/submit` | `{ orderId, score, comment }` | 提交评价（仅 PAID，一单一评） | C |
| GET | `/api/admin/review/list` | Query: `score`（可选） | 评价列表，按评分筛选 | B |

#### AI 代理接口

**知识库 AdminKnowledgeController `/api/admin/knowledge`**

| 方法 | 路径 | 入参 | 说明 |
|------|------|------|------|
| GET | `/list` | — | 文档元数据列表（标题/版本/状态/分块数/时间） |
| POST | `/parse-file` | Multipart: file（仅 .docx / .pdf） | Java 本地解析为纯文本（POI / PDFBox），供前端预填内容 |
| POST | `/upload` | `{ title, content, metadata?, version?, status?, effectiveFrom? }` | 写入元数据表，并代理调用 Python `/ai/knowledge/process` 向量化 |

**备菜预测 AdminPredictController `/api/admin/predict`**（全部代理至 Python）

| 方法 | 路径 | 入参 | 对应 Python 接口 |
|------|------|------|------------------|
| POST | `/trigger` | `{ targetDate?, dishId? }` | `POST /ai/predict/trigger` |
| GET | `/result` | Query: `targetDate` | `GET /ai/predict/result?target_date=` |
| GET | `/status` | Query: `taskId` | `GET /ai/predict/status?task_id=` |
| POST | `/confirm` | `{ recordId, finalQuantity, confirmedBy }` | `POST /ai/predict/confirm` |

#### 数据代理 DishProxyController `/api/proxy/dish`（供 Python 客服工具回调，JWT 放行）

| 方法 | 路径 | 入参 | 说明 |
|------|------|------|------|
| GET | `/inventory` | Query: `dishName` | 按菜名查实时库存 |
| GET | `/ingredients` | Query: `dishName` | 按菜名查配料/过敏原 |

### 7.3 Python 端接口（FastAPI，默认端口 8000）

| 方法 | 路径 | 入参 | 响应 | 说明 |
|------|------|------|------|------|
| GET | `/` | — | 欢迎信息 | 健康检查 |
| POST | `/ai/chat` | `{ message }` | SSE 流：`data: {"content": "..."}` × N + `data: [DONE]` | AI 客服（语义缓存命中时按 10 字符切片模拟流式） |
| POST | `/ai/knowledge/process` | `{ content, metadata?, version?, status?, effective_from? }` | `{ chunk_count, document_id, ... }` | 分块 → Embedding → Milvus；每次生成新 document_id |
| POST | `/ai/knowledge/search` | `{ query, top_k=3, version? }` | Top-K 片段 | 向量检索，可按版本过滤 |
| POST | `/ai/predict/trigger` | `{ target_date?, dish_id? }` | `{ task_id }` | BackgroundTasks 异步执行；默认预测明日全部在售菜品 |
| GET | `/ai/predict/status` | Query: `task_id` | 任务进度 | 进度存 Redis `predict:task:{task_id}`，TTL 1h |
| GET | `/ai/predict/result` | Query: `target_date` | 预测记录列表 | 读 MySQL `ai_prediction_record` |
| POST | `/ai/predict/confirm` | `{ record_id, final_quantity, confirmed_by }` | — | 人工确认/覆盖预测量 |

### 7.4 WebSocket 协议

| 端点 | 客户端 | 会话模型 | 事件 |
|------|--------|----------|------|
| `/ws/kitchen-board?token={jwt}` | B 端厨房看板 | 单会话（新连接踢掉旧连接） | 服务端 → 客户端：`{"type":"NEW_ORDER","order":{...}}`（下单/加菜时推送） |
| `/ws/customer?token={jwt}` | C 端 | 按 userId 绑定多会话 | 服务端 → 客户端：`{"type":"ORDER_SERVED","message":"您的订单已出餐，请取餐"}` |

**握手鉴权（`WebSocketAuthInterceptor`）**：握手阶段校验 URL `?token=` 中的 JWT，缺失/非法返回 401；`/ws/kitchen-board` 额外要求 `role=ADMIN`，否则 403；`/ws/customer` 的 userId 由 token 解析写入会话属性（不再信任客户端自报），Handler 建连时从属性读取绑定。

**客户端规范（B 端实现）**：连接 URL 携带 `?token=`；每 30s 发送 `{"type":"PING"}` 心跳；断线后延迟 3s 重连，最多 10 次；`onOpen` 后拉取 HTTP 快照（`/api/kitchen-board/orders`）补齐。

> 说明：出餐与撤销由管理员在 Web 页面内主动操作，前端操作成功后本地移除卡片，故服务端不推送对应事件；重连快照机制可兜底任何遗漏。

---

## 8. 核心机制设计

### 8.1 JWT 鉴权

- HS256 签名；载荷含 `userId`、`role`；有效期 24h（`smart-kitchen.jwt.expiration=86400000` ms）。
- `JwtInterceptor` 拦截 `/api/**`，从 `Authorization: Bearer` 解析；失败返回 401 JSON。
- 解析结果写入 `UserContext`（ThreadLocal），业务代码经 `UserContext.getUserId()` 获取当前用户；`afterCompletion` 清理防内存泄漏。
- 放行：`/api/auth/**`、`/api/seat/**`、`/api/proxy/**`、`/error`。
- WebSocket 不走 HTTP 拦截器，改由 `WebSocketAuthInterceptor` 在握手阶段校验 `?token=`（详见 7.4）。

### 8.2 库存扣减（Redis + Lua + MySQL 双写）

**扣减脚本 `scripts/deduct_stock.lua`**：先遍历全部 key 校验库存（key 不存在或库存 < 需求量时返回 `-i`，i 为首个不足的菜品序号），全部通过后再逐个 `DECRBY`，成功返回 `1`——「先检后扣」保证原子性，不会扣一半。

**回滚脚本 `scripts/return_stock.lua`**：对每个 key `INCRBY` 对应数量。

**下单链路**：

```
逐菜品校验（下架菜：不计价不扣库存，名称标记「（已下架）」）
  → Redis key dish:stock:{dishId} 不存在则以 pms_dish.daily_stock 初始化
  → 执行 deduct_stock.lua（不足则报错并指明菜品）
  → 落库订单 + 明细
  → MySQL 同步扣减：UPDATE pms_dish SET daily_stock = daily_stock - ? WHERE id = ? AND daily_stock >= ?
     （affectedRows = 0 视为失败）
  → 任一异常：执行 return_stock.lua 回滚 Redis，事务回滚 MySQL
  → 成功：WS 推看板 NEW_ORDER + MQ 发延迟消息
```

### 8.3 WebSocket 实时推送

- 两个 Handler：`KitchenBoardWebSocketHandler`（单会话广播）、`CustomerWebSocketHandler`（按 userId 定向）。
- 推送时机：`OrderServiceImpl` 内下单、加菜（→看板 `NEW_ORDER`），出餐（→顾客 `ORDER_SERVED`）。
- 推送失败不影响主流程（try-catch 吞没）。
- 前端容错：心跳 + 有限次重连 + HTTP 快照补齐。

### 8.4 模拟支付

- 不接第三方支付；流水号 = `SIM_` + `System.currentTimeMillis()`。
- ORDERED 状态结账仅登记 `pay_time`/`payment_trade_no`；出餐时检测 `pay_time != null` 自动流转 PAID。
- 结账时级联合并支付全部未支付子订单（流水号 `_1`、`_2` 后缀）。
- 幂等与并发：应用层预校验（状态合法 + `pay_time` 为空）只做友好报错，最终裁决下沉为 SQL 条件更新——`UPDATE oms_order ... WHERE id=? AND status IN (0,10) AND pay_time IS NULL`（SERVED 经 `CASE WHEN` 同步流转 PAID），影响行数 0 即被并发抢先，报错提示；`uk_payment_trade_no` 唯一索引兜底。
- 扩展点：未来接入微信支付可抽取支付策略接口替换实现。

### 8.5 RabbitMQ 订单超时自动取消

**方案选型**：DLX（死信交换机）+ TTL，无需额外插件，消息持久化 + 手动 ACK 保证可靠性。

**拓扑**：

```
submitOrder() 落库成功
  → convertAndSend(order.delay.exchange, rk=order.delay, body=orderId)
  → order.delay.queue（x-message-ttl=1800000ms，DLX=order.timeout.exchange，DLK=order.timeout）
  → 30 分钟过期 → order.timeout.exchange → order.timeout.queue
  → OrderTimeoutConsumer（手动 ACK）：
       查订单 → ORDERED/SERVED → cancelOrder()（还库存+级联子订单）
              → PAID/CANCELLED/不存在 → 跳过并 ACK
              → 消费异常 → basicNack(requeue=false) → 经超时队列 DLX 投递至 DLQ 待人工补偿
```

另配异常兜底 DLX/DLQ（`order.timeout.dlx` / `order.timeout.dlq`），消费异常消息不直接丢弃而是转入 DLQ。连接配置开启 `publisher-confirm-type: correlated` 与 `publisher-returns: true`。

### 8.6 LangGraph 备菜预测工作流

实现：`app/agents/predict_agent.py`，`StateGraph(PredictState)` 编译为 `predict_subgraph`，对每道在售菜品（`pms_dish.status=1`）执行一次 `ainvoke`。

**图拓扑**：

```
START ─┬─→ get_sales_30d ──→ time_series_predict ─┐
       ├─→ get_tomorrow_weather ──────────────────┤
       ├─→ get_holiday_info ──────────────────────┼─→ llm_adjust ─→ save_result ─→ END
       └─→ get_recent_reviews ────────────────────┘
```

**节点实现**：

| 节点 | 实现 |
|------|------|
| `get_sales_30d` | PyMySQL 汇总 `oms_order_detail` 近 30 天日销量 |
| `get_tomorrow_weather` | OpenWeatherMap `/data/2.5/forecast`（5 日/3 小时间隔），按目标日期过滤聚合为日级（城市由 `WEATHER_CITY` 配置） |
| `get_holiday_info` | ModelScope MCP 服务，标准 JSON-RPC：`initialize`（取 `Mcp-Session-Id`）→ `tools/call`（工具 `holiday_info`） |
| `get_recent_reviews` | PyMySQL 查近 10 条评价及均分 |
| `time_series_predict` | 30 天均值/中位数/近 7 日均值 → 基础量；数据不足走冷启动降级（见下） |
| `llm_adjust` | 外置 Prompt（`app/prompts/predict_llm_prompt.txt`）+ 四维数据 → LLM → JSON；60s 超时/异常 → 回退时序结果（confidence=0.5，reasoning 注明降级） |
| `save_result` | upsert `ai_prediction_record`（按 `uk_date_dish` 唯一键） |

**冷启动降级链**：≥7 天销量（30 天统计）→ <7 天（近 N 天均值）→ 同分类菜品 30 天日均 → `new_product_initial_stock` → `daily_stock` → 兜底常量 20。Prompt 中将当前降级级别告知 LLM，由其在该基准上叠加修正。

**触发**：

- 定时：APScheduler Cron 每日 02:00（FastAPI lifespan 启停）。
- 手动：`POST /ai/predict/trigger` → BackgroundTasks 异步执行，返回 `task_id`；进度写 Redis（`predict:task:{task_id}`，TTL 1h），前端每 3s 轮询 `/ai/predict/status`。

### 8.7 AI 客服（ReAct Agent）

- 框架：`langgraph.prebuilt.create_react_agent`；LLM 为 `ChatOpenAI`（DeepSeek 兼容接口，`streaming=True`）。
- System Prompt 外置 `app/prompts/cs_agent_prompt.txt`，约束：单轮 ≤3 次工具调用、同一工具单轮 ≤1 次、工具无结果给固定话术。
- 工具：

| 工具 | 实现 |
|------|------|
| `search_dish_by_preference(taste/描述)` | `rag_service` Milvus 检索 |
| `check_dish_inventory(dish_name)` | HTTP 调 Java `/api/proxy/dish/inventory` |
| `get_dish_ingredients(dish_name)` | HTTP 调 Java `/api/proxy/dish/ingredients` |

- **SSE 输出**：`StreamingResponse(media_type="text/event-stream")`；`astream_events(version="v1")` 监听 `on_chat_model_stream` 逐块推 `data: {"content": ...}`，结束推 `data: [DONE]`。
- **语义缓存**（`semantic_cache_service.py`，替代原 `ai_chat_cache:{md5}` 精确匹配）：
  - **存储**：Milvus Lite 独立集合 `ai_chat_semantic_cache`（与 `kitchen_knowledge` 同库不同集合），条目 = 问题向量（百炼 embedding，1024 维）+ 回答 + `kb_version` 指纹 + `created_at`。
  - **命中**：问题转 embedding 后向量检索 Top-1，余弦相似度 ≥ `SEMANTIC_CACHE_THRESHOLD`（默认 0.92）即命中，差字/标点/语序不再导致 miss；命中时按 10 字符切片 + `asyncio.sleep(0.01)` 模拟流式，不调 LLM。
  - **只缓存非动态回答**：流式过程中监听 `on_tool_start`，调用了实时数据工具（`check_dish_inventory` / `get_dish_ingredients`，回调 Java 查 MySQL）的回答不写入，避免向其他用户散发过期库存；纯知识问答与 RAG 推荐（`search_dish_by_preference` 查知识库）可缓存。
  - **失效**：`kb_version` = `ai_knowledge_document` 全表 `(id, version, status)` 的哈希指纹（Redis 缓存 60s），知识库新增/归档/改版后旧条目因 filter 不匹配自动失效；另有 `SEMANTIC_CACHE_TTL_DAYS`（默认 7 天）保鲜期。
  - **降级**：embedding / Milvus / MySQL 任一异常均静默降级为不缓存（直接走 LLM）；指纹获取失败退化为 `unknown`（缓存可用，仅暂失知识库变更感知）。
  - **成本**：未命中只多一次 embedding 调用（lookup 算好的向量传入 store 复用，不重复计费），命中省一整次 LLM 生成。

### 8.8 RAG 知识库

- **分块**：`RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)`。
- **Embedding**：自定义 `DashScopeEmbeddings`（继承 `OpenAIEmbeddings`，逐条调用以适配百炼限制），维度 1024。
- **向量库**：Milvus Lite（本地文件 `data/milvus.db`），集合 `kitchen_knowledge`：`id`（auto_id 主键）+ `vector`（1024 维）+ 动态字段（`document_id` / `chunk_index` / `text` / `version` / `status` / `effective_from` / `metadata`）。
- **版本管理**：每次 `process` 生成新 `document_id`（UUID），旧版本 chunk 保留；检索可传 `version` 过滤。元数据（标题/分块数/版本/状态）由 Java 侧写 `ai_knowledge_document` 表。
- **文件解析**：.docx/.pdf 由 Java 端（POI / PDFBox）解析为文本，再走统一上传流程，Python 只接收纯文本。

### 8.9 降级策略汇总

| 场景 | 降级 |
|------|------|
| LLM 超时（60s）/ 异常 / JSON 非法 | 采用时序预测结果，confidence=0.5 |
| 天气 / 节假日 API 不可用 | 跳过该维度，Prompt 注明缺失 |
| 预测无历史销量 | 五级冷启动降级链（见 8.6） |
| 客服 LLM 成本高 | 语义缓存（Milvus 向量检索，相似度 ≥0.92 命中）；embedding/向量库异常时降级为不缓存直调 LLM |
| 客服工具无结果 | Prompt 固定话术，不编造 |
| WS 推送失败 | 捕获异常不影响交易主流程；前端快照兜底 |
| MQ 消费异常 | 记录日志后 ACK，人工补偿 |
| 小程序低版本不支持分块传输 | 降级为普通 POST 整包返回 |

---

## 9. 环境配置

### 9.1 Java（application.yml，`${VAR:default}` 形式支持环境变量覆盖）

| 配置项 | 说明 | 默认 |
|--------|------|------|
| `server.port` | 服务端口 | 8080 |
| `spring.datasource.*` | MySQL（`${DB_HOST}:3306/smart_kitchen`，Hikari 池 5–20） | localhost |
| `spring.data.redis.*` | Redis 连接（Lettuce 池） | localhost:6379 |
| `spring.rabbitmq.*` | MQ 连接 + publisher-confirm/returns | localhost:5672 |
| `smart-kitchen.jwt.secret` / `jwt.expiration` | JWT 密钥 / 有效期 ms | — / 86400000 |
| `smart-kitchen.python-service.url` | Python 服务地址 | `http://localhost:8000` |
| `smart-kitchen.wechat.app-id/app-secret` | 微信小程序凭证 | 环境变量 |
| `smart-kitchen.oss.*` | OSS endpoint / bucket / AK | 环境变量 |
| `spring.servlet.multipart.*` | 上传大小限制 | 10MB |

### 9.2 Python（.env / config.py）

| 配置项 | 说明 | 默认 |
|--------|------|------|
| `DB_HOST/DB_PORT/DB_USER/DB_PWD/DB_NAME` | MySQL | localhost / smart_kitchen |
| `REDIS_HOST/REDIS_PORT/REDIS_PWD/REDIS_DB` | Redis | localhost:6379 |
| `LLM_API_KEY/LLM_BASE_URL/LLM_MODEL` | LLM（DeepSeek 兼容） | `https://api.deepseek.com` |
| `EMBEDDING_API_KEY/EMBEDDING_BASE_URL/EMBEDDING_MODEL` | Embedding（百炼兼容） | — |
| `WEATHER_API_KEY/WEATHER_CITY` | OpenWeatherMap | Shenzhen |
| `JAVA_API_URL` | Java 服务地址 | `http://localhost:8080` |
| `APP_PORT` | FastAPI 端口 | 8000 |

### 9.3 前端

| 端 | 配置 |
|----|------|
| admin-web | `.env.development/.env.production`：`VITE_API_BASE_URL`、`VITE_WS_URL`；`vite.config.js` 代理 `/api`、`/ws` |
| miniprogram | `app.js` globalData：`apiBase`（Java）、`aiBase`（Python）、`wsBase` |

---

## 10. 测试方案

| 层 | 内容 | 位置 |
|----|------|------|
| Java 集成测试 | 12 个 Controller 集成测试（认证/座位/分类/菜品/代理/订单/评价/库存/知识库/预测/Phase6 接口） | `smart-kitchen/src/test/.../controller/` |
| Java 单元测试 | `CategoryServiceTest`、`OrderTimeoutConsumerTest`（正常超时取消、已支付跳过、已撤销跳过、订单不存在、异常 Nack 进 DLQ） | `.../service/` |
| Java 并发测试 | `OrderServiceConcurrencyTest`（CountDownLatch 多线程并发支付/出餐/支付vs撤销竞争，断言条件更新恰好一笔生效）、`OrderServiceSubmitRollbackTest`（MySQL 扣减返回 0 → 订单不落库 + Redis 回滚脚本执行） | `.../service/` |
| WebSocket 鉴权测试 | `WebSocketAuthInterceptorTest`（合法/缺失/非法 token、越权连看板、会话按 token userId 绑定） | `.../config/` |
| 测试环境 | H2 内存库 + `application-test.yml`（`schema-h2.sql` / `data-h2.sql`）；测试时 MQ listener 关闭自动启动 | `src/test/resources/` |
| Python 测试 | MCP 工具（天气/节假日）、预测链路、RAG 接口；库存 Lua 脚本 50 线程并发压测 | `smart-kitchen-ai/tests/`、`test_rag.py`、`tests/test_stock_concurrency.py` |
| 接口压测 | `/api/order/submit` 多线程并发下单（真实应用 + MySQL/Redis/RabbitMQ），断言不超卖并输出延迟分位 | `tests/load_order_submit.py` |
| 小程序校验 | 静态结构校验（页面三件套、tabBar、工具方法、登录流程关键函数） | `tests/test_miniprogram.py` |

---

## 11. 部署规划（进行中）

当前各服务以本地进程方式运行（Java 8080 / Python 8000 / Vite 5173）。统一容器化部署为本项目尚未完成的收尾工作，规划如下：

| 组件 | 容器化内容 |
|------|-----------|
| MySQL 8 | 官方镜像 + 初始化挂载 `schema.sql` + 数据卷 |
| Redis | 官方镜像 |
| RabbitMQ | `rabbitmq:management` 镜像 |
| smart-kitchen | Maven 多阶段构建 → JRE 21 运行时 |
| smart-kitchen-ai | Python 镜像 + `requirements.txt`（注意 Milvus Lite 数据文件挂载卷） |
| admin-web | `npm run build` → Nginx 托管静态资源 + 反代 `/api`、`/ws` |
| miniprogram | 不容器化，部署时切换 `apiBase`/`aiBase` 为线上域名 |
| 编排 | 根目录 `docker-compose.yml` 统一编排以上服务，环境变量经 `.env` 注入 |

> 现状：`smart-kitchen-ai/Dockerfile` 为占位空文件，compose 编排与 Nginx 配置待编写。
