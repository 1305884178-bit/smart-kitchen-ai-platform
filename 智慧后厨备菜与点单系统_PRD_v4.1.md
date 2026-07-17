# 《智慧后厨备菜与点单系统》产品需求文档 (PRD v4.1)

---

## 文档修订记录

| 版本 | 日期 | 修订内容 |
|------|------|----------|
| v1.0 | - | 初版 |
| v2.0 | 2026-07-14 | 明确技术选型、简化范围、补充KDS设计、风险清单 |
| v3.0 | 2026-07-14 | 简化B端架构、自动接单、座位号、状态机极简化、LangGraph备菜、评价闭环、Java/Python边界 |
| v4.0 | 2026-07-14 | **业务模式变更为「先做后付」**：下单→厨房看板完成→结账；新增加菜功能；完成按钮移至厨房看板；撤销留在订单管理；重新设计状态机 |
| v4.1 | 2026-07-15 | 订单列表菜品缩略展示、LangGraph明确单Agent架构+MCP调用、AI客服改为全量热点缓存、删除sys_operation_log（单管理员场景） |

---

## 一、项目概述

本项目为中型餐饮门店提供一套数字化点单与后厨管理系统，核心解决以下痛点：

1. **高峰期点单并发导致库存超卖**——Redis + Lua 原子预扣库存。
2. **凭经验备菜导致食材损耗**——LangGraph 多节点 AI 预测（销量、天气、节假日、顾客评价）辅助决策。
3. **顾客信息获取低效**——AI 客服（单 Agent + Function Calling + RAG 知识库）提供菜品咨询与推荐。

### 1.1 业务模式

**先做后付**：顾客下单后厨房开始制作，菜上齐后结账支付。与传统餐厅真实流程一致。

### 1.2 范围约定

| 维度 | 约定 |
|------|------|
| 门店 | 单门店 |
| 用餐类型 | 堂食，顾客输入座位号 |
| 支付时机 | 餐后结账（非预支付） |
| 库存粒度 | 菜品级库存 |
| B 端终端 | 一套 PC Web 管理端（含厨房看板子页面） |
| B 端用户 | 单管理员（不设多员工/多角色） |
| 后厨接单 | 无需接单，厨房看板显示待制作订单 |

### 1.3 技术栈分工

| 层 | 技术 | 职责 |
|----|------|------|
| 传统后端 | **Java**（Spring Boot） | 鉴权、菜品/订单/库存CRUD、WebSocket推送 |
| AI 服务 | **Python**（FastAPI） | AI客服Agent、LangGraph备菜预测、RAG文档向量化与检索 |
| 消息队列 | RabbitMQ | 订单超时延迟消息 |
| 缓存 | Redis | 库存预扣、JWT黑名单、AI回答缓存 |
| 数据库 | MySQL | 全部业务表 |
| 向量库 | Milvus | RAG文档向量存储与检索 |
| 跨语言通信 | HTTP（Java 调 Python） | - |

---

## 二、用户角色定义

| 角色 | 接入端 | 核心职责 |
|------|--------|----------|
| **顾客 (C端)** | 小程序 / H5 | 浏览菜单、下单、加菜、结账支付、AI客服咨询、查看订单状态、餐后评价 |
| **餐厅管理员 (B端)** | PC Web 端 | 厨房看板（完成出餐）、订单管理（撤销/查看）、菜品管理、库存管理、AI备菜预测触发与确认、AI知识库维护 |

---

## 三、核心功能模块详细设计

---

### 模块 1：顾客点单与订单流转

#### 1.1 下单流程

- **前置条件**：用户已登录（JWT鉴权），菜品状态为「起售」。
- **操作流程**：
  1. 用户选择菜品，输入**座位号**（如 A05），加入购物车。
  2. 点击「下单」，前端校验菜品是否下架（弱校验，仅提示）。
  3. 后端：
     - 执行 **Redis + Lua 库存预扣减**（原子操作）。
     - 预扣成功 → 生成订单，状态置为 `ORDERED`。
  4. 返回订单信息给前端。

#### 1.2 加菜流程

- **入口**：顾客在 ORDERED 或 SERVED 状态时，订单详情页显示 [加菜] 按钮。
- **操作流程**：
  1. 点击加菜 → 再次进入菜品选择页。
  2. 确认加菜 → 后端追加 `oms_order_detail` 记录，重新执行库存预扣。
  3. 如果当前状态为 SERVED，自动回退到 ORDERED（新菜需要做）。
- **边界**：PAID、CANCELLED 状态下 [加菜] 按钮不显示。

#### 1.3 结账支付

- **入口**：ORDERED 或 SERVED 状态下，订单详情页显示 [结账] 按钮。
- **操作流程**：
  1. 点击结账 → 跳转支付页。
  2. 支付成功 → 状态变为 `PAID`，顾客页显示「已结账」+ [评价] 入口。
  3. 通知厨房看板该座位号订单已结账（卡片消失）。

#### 1.4 订单状态机（v4.0）

```
ORDERED（已下单）
  │  顾客可见：[加菜] [结账]
  │  厨房看板：显示该座位号菜品卡片 + [完成]按钮
  │
  ├── 厨房看板点[完成] ──→ SERVED（已上菜，待结账）
  │     │  顾客可见：[加菜] [结账]
  │     │  厨房看板：不显示（菜已上齐）
  │     │
  │     ├── 顾客[结账]支付 ──→ PAID（已结账）
  │     │    顾客可见：[评价]
  │     │    厨房看板：不显示
  │     │
  │     ├── 顾客[加菜] ──→ ORDERED（新菜需要做）
  │     │    厨房看板：重新出现该座位号卡片
  │     │
  │     └── 管理员[撤销] ──→ CANCELLED（已撤销）
  │          厨房看板：卡片消失
  │
  ├── 顾客[结账]支付 ──→ PAID（跳过SERVED）
  │    厨房看板：卡片消失
  │
  └── 管理员[撤销] ──→ CANCELLED（已撤销）
       厨房看板：卡片消失
```

**状态值定义**：

| 状态码 | 状态名 | 触发者 | 顾客端按钮 | 厨房看板 |
|--------|--------|--------|------------|----------|
| 0 | ORDERED | 顾客下单 | [加菜] [结账] | 显示卡片 + [完成] |
| 10 | SERVED | 厨房看板点完成 | [加菜] [结账] | 不显示 |
| 20 | PAID | 顾客支付 | [评价] | 不显示 |
| 90 | CANCELLED | 管理员撤销 | 无 | 不显示 |

**关键规则**：
- [加菜] 按钮在 ORDERED、SERVED 状态均可用，PAID、CANCELLED 不可用。
- [结账] 按钮在 ORDERED、SERVED 状态均可用。ORDERED 状态下结账跳过 SERVED 直达 PAID。
- SERVED 状态下加菜，状态回退到 ORDERED，厨房看板重新出现。
- 状态变更 SQL 始终带旧状态条件（乐观锁）：`UPDATE oms_order SET status=? WHERE id=? AND status=?`。
- 撤销附带 `cancel_reason` 字段（`MERCHANT_CANCEL`）。

#### 1.5 顾客端页面状态流转

```
[点单页] → 选菜输入座位号 → [下单]
    │
    ▼
[订单详情页 - ORDERED]
  显示：已下单的菜品列表
  按钮：[加菜] [结账]
    │
    ├─ 厨房看板点[完成] ──→ [订单详情页 - SERVED]
    │                        显示：菜品列表（状态：已上菜）
    │                        按钮：[加菜] [结账]
    │                          │
    │                          ├─ 点[加菜] → 回到[订单详情页 - ORDERED]
    │                          └─ 点[结账]支付 → [订单详情页 - PAID]
    │                                             显示：已结账
    │                                             按钮：[评价]
    │
    ├─ 点[结账]支付 ──→ [订单详情页 - PAID]（跳过SERVED）
    │
    └─ 管理员撤销 ──→ [订单详情页 - CANCELLED]
                      显示：商家已撤销
```

---

### 模块 2：管理端——厨房看板与订单管理

#### 2.1 厨房看板子页面

- **入口**：管理端左侧菜单「厨房看板」，独立路由 `/kitchen-board`。
- **设计原则**：大字号、适合挂大屏或平板放后厨。
- **展示内容**：所有 `ORDERED` 状态订单，按下单时间升序排列。每个订单一张卡片。
- **卡片信息**：座位号、下单时间、菜品明细（菜名 × 数量）、备注。
- **操作**：每张卡片有 [完成] 按钮。
  - 点击后状态从 ORDERED → SERVED，卡片从看板消失。
  - 反馈：按钮点击后短暂显示「已出餐」然后卡片淡出。
- **实时更新**：WebSocket 推送。
  - `order_created`：新订单 → 新增卡片（高亮动画）。
  - `order_updated`：该座位号追加菜品 → 卡片内容更新。
  - `order_paid`：顾客直接结账 → 卡片消失。
  - `order_cancelled`：管理员撤销 → 卡片消失。

**WebSocket 事件模型**：

```json
{
  "event_type": "order_created | order_updated | order_paid | order_cancelled",
  "order_id": 123,
  "order_version": 2,
  "seat_number": "A05",
  "dish_list": [
    {"dish_name": "水煮鱼", "quantity": 1, "remark": "加辣"},
    {"dish_name": "米饭", "quantity": 2, "remark": ""}
  ],
  "create_time": "2026-07-14T12:08:30+08:00"
}
```

- 断线重连后拉一次当前 ORDERED 订单 HTTP 快照。
- 按 `order_id + order_version` 去重与乱序处理。

#### 2.2 订单管理页

- **功能**：列表展示全部订单（按时间倒序），筛选条件：状态、座位号。
- **菜品列展示规则**：取第一道菜名 + `等N道菜`（N 为明细总数）。若仅 1 道菜，显示菜名。点 [详情] 展开全部菜品明细。
- **操作**：
  - 查看订单详情。
  - 对 ORDERED 或 SERVED 状态的订单：点 [撤销] → 状态变为 CANCELLED，填写原因（选填）。
  - PAID 和 CANCELLED 状态为终态，不可再操作。
- **更新方式**：定时轮询（30s）或手动刷新。

#### 2.3 两个页面的操作分工

```
┌────────────────────────────────────────────────┐
│                厨房看板 /kitchen-board           │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐    │
│  │ 座位 A05  │  │ 座位 B02  │  │ 座位 C03  │    │
│  │ 水煮鱼×1  │  │ 烤鱼×1   │  │ 宫保鸡丁×1│    │
│  │ 米饭×2   │  │ 啤酒×2   │  │ 汤×1     │    │
│  │ [完成]   │  │ [完成]   │  │ [完成]   │    │
│  └──────────┘  └──────────┘  └──────────┘    │
│                                                  │
│  厨师/管理员在此操作：菜做完点「完成」              │
└────────────────────────────────────────────────┘

┌────────────────────────────────────────────────┐
│                订单管理 /admin/orders            │
│                                                  │
│  全部 | ORDERED | SERVED | PAID | CANCELLED     │
│                                                  │
│  座位号 | 菜品            | 金额   | 状态     | 操作               │
│  A05   | 水煮鱼 等3道菜  | ¥188  | ORDERED | [详情] [撤销]       │
│  B02   | 烤鱼（单点）    | ¥128  | SERVED  | [详情] [撤销]       │
│  C03   | 宫保鸡丁 等2道菜 | ¥96   | PAID    | [详情]             │
│                                                  │
│  管理员在此操作：异常情况点「撤销」                  │
└────────────────────────────────────────────────┘
```

---

### 模块 3：AI 智能备菜预测系统（LangGraph 多节点）

**技术选型**：LangGraph Supervisor 模式（**单 Agent + 多函数节点**，非多 Agent）。

> 整个图中只有 `supervisor` 和 `llm_adjust` 两个节点调用 LLM，其余节点均为确定性 Python 函数。天气和节假日数据通过 **MCP（Model Context Protocol）** 调用外部 API，但调用方是函数节点而非独立 LLM Agent。这样做的好处：成本可控（仅 2 次 LLM 调用）、时延低、排查简单。如未来数据维度增多，可在图中扩展新节点而不需重构。

#### 3.1 LangGraph 节点图

```
START
  │
  ▼
[supervisor] ── 分析任务 → 决定并行调用数据节点
  │
  ├─→ [get_sales_30d]      拉取各菜品过去30天日销量
  ├─→ [get_tomorrow_weather] 调用第三方API获取明日天气
  ├─→ [get_holiday_info]     判断明日是否节假日/周末
  └─→ [get_recent_reviews]   拉取各菜品近30天平均评分与差评率
  │
  ▼ (并行完成后回到 supervisor 汇总)
  │
  ▼
[time_series_predict] ── 加权移动平均计算每道菜「基础预测量」
  │
  ▼
[llm_adjust] ── 组装Prompt（基础预测 + 天气 + 节日 + 评价）→ 调大模型 → 输出JSON
  │
  ▼
[save_result] ── 写入 ai_prediction_record 表
  │
  ▼
END
```

#### 3.2 节点说明

| 节点 | 类型 | 说明 |
|------|------|------|
| `supervisor` | LLM 决策 | 分析日期，决定调用哪些数据节点 |
| `get_sales_30d` | Python函数 | 查 MySQL 汇总近30天各菜日销量 |
| `get_tomorrow_weather` | Python函数（MCP） | 通过 MCP 调第三方天气 API |
| `get_holiday_info` | Python函数（MCP） | 通过 MCP 判断节假日/周末 |
| `get_recent_reviews` | Python函数 | 查 MySQL 汇总近30天各菜评分均值 |
| `time_series_predict` | Python函数 | 加权移动平均（WMA） |
| `llm_adjust` | LLM调用 | Prompt工程，大模型微调基础预测 |
| `save_result` | Python函数 | 写入DB |

#### 3.3 降级策略

| 异常 | 处理 |
|------|------|
| 外部API不可用（天气/节假日） | 跳过该维度，Prompt注明「数据缺失」，继续 |
| `llm_adjust` 超时（>10s）或返回格式非法 | 降级使用 `time_series_predict` 结果，记录告警 |
| JSON格式校验失败 | 一次修复重试 → 仍失败 → 降级 |

核心原则：**备菜任务绝对不能中断**。

#### 3.4 LLM输出Schema

```json
{
  "dish_name": "烤鱼",
  "base_quantity": 50,
  "final_quantity": 65,
  "reasoning": "近期评分4.9、暴雨降温预计客流增30%，建议增加备货",
  "confidence": 0.85
}
```

- `reasoning` 可选；`confidence` < 0.4 在看板标记「低置信度」。

#### 3.5 触发与人工干预

- **触发**：每日凌晨 02:00 Cron，或管理员手动触发。
- **看板展示**：菜品 | 基础预测 | AI建议量 | 近期评分 | 推理 | 当前库存 | 建议采购量。
- **公式**：建议采购量 = max(0, 最终确认量 - 当前库存)。
- **人工覆盖**：可修改最终确认量，记录操作人。

#### 3.6 评价反馈闭环

- 评价来源：模块 4.3 餐后评价 `oms_review`。
- 数据通路：`oms_review` → `get_recent_reviews` 节点 → `llm_adjust` Prompt。
- 面试可讲：「引入评价数据后，系统能感知菜品口碑变化对销量的影响，是真正的数据闭环。」

---

### 模块 4：AI 智能客服与 RAG 知识库

#### 4.1 顾客端智能客服（单Agent + Function Calling）

| 工具 | 数据来源 | 说明 |
|------|----------|------|
| `search_dish_by_preference(taste, ingredient)` | RAG知识库（Milvus） | 语义检索推荐菜品 |
| `check_dish_inventory(dish_name)` | MySQL（Java接口） | 实时库存查询 |
| `get_dish_ingredients(dish_name)` | MySQL `pms_dish.ingredients` | 配料查询（过敏原） |

- 结构化数据（配料、库存）走 MySQL；开放性问题（推荐）走 RAG。
- **流式输出（SSE）**：Java 转发 Python Agent 的 SSE 流到前端，首字 < 1s。
- **热点缓存（全量用户共享）**：对用户问题做文本归一化（去标点、trim、小写），key 为 `ai_cache:{sha256(normalized_question)}`，缓存 LLM 回答 10 分钟。热点问题如「有什么推荐菜」「水煮鱼辣不辣」「开到几点」等高频询问命中率远超按用户维度缓存。后续可升级为语义相似度去重（embedding + cosine > 0.95 即命中）。
- **过敏原过滤**：工具层硬规则过滤，不依赖模型判断。

#### 4.2 知识库管理（B端维护→C端使用）

- 管理端上传 PDF/Word 菜品介绍、门店特色、推荐搭配等。
- Python `RecursiveCharacterTextSplitter`（chunk_size=500, overlap=50）→ Embedding → Milvus。
- 版本管理：`ai_knowledge_document` 表支持 `version`、`status`、`effective_from`。

#### 4.3 餐后评价

- **入口**：PAID 状态订单详情页显示 [评价] 按钮。
- **内容**：1-5 星评分 + 可选文字评价。
- **数据表**：`oms_review`。
- **用途**：管理员查看；评价数据流入 LangGraph 预测管道。

---

## 四、核心数据模型

### 4.1 `oms_order`（订单主表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | bigint PK | 自增 |
| `order_no` | varchar(32) UNIQUE | 订单号 |
| `user_id` | bigint | 下单用户 |
| `seat_number` | varchar(8) | 座位号（如 A05） |
| `total_amount` | decimal(10,2) | 订单总额 |
| `status` | tinyint | 0-ORDERED / 10-SERVED / 20-PAID / 90-CANCELLED |
| `cancel_reason` | varchar(16) | MERCHANT_CANCEL |
| `pay_time` | datetime | 支付时间（结账时填充） |
| `payment_trade_no` | varchar(64) UNIQUE | 支付流水号（幂等键） |
| `complete_time` | datetime | 厨房完成时间 |
| `operator_id` | bigint | 最后操作管理员 |
| `remark` | varchar(256) | 顾客备注 |
| `create_time` | datetime | 下单时间 |
| `update_time` | datetime | 最后更新时间 |

### 4.2 `oms_order_detail`（订单明细表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | bigint PK | 自增 |
| `order_id` | bigint FK | 关联订单 |
| `dish_id` | bigint | 菜品ID |
| `dish_name` | varchar(64) | 菜品名称快照 |
| `quantity` | int | 数量 |
| `price` | decimal(10,2) | **购买时快照价格** |
| `is_added` | tinyint | 0-首单 / 1-加菜追加 |
| `create_time` | datetime | - |

### 4.3 `oms_review`（评价表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | bigint PK | 自增 |
| `order_id` | bigint UNIQUE | 关联订单（一单一评） |
| `user_id` | bigint | 评价用户 |
| `score` | tinyint | 1-5星 |
| `comment` | varchar(512) | 文字评价（可选） |
| `create_time` | datetime | - |

### 4.4 `pms_dish`（菜品表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | bigint PK | 自增 |
| `name` | varchar(64) | 菜品名称 |
| `category_id` | bigint | 分类ID |
| `price` | decimal(10,2) | 售价 |
| `image` | varchar(256) | 图片URL |
| `status` | tinyint | 1-起售 / 0-停售 |
| `daily_stock` | int | 每日库存 |
| `alert_threshold` | int | 预警阈值 |
| `ingredients` | text | 配料JSON数组 |
| `new_product_initial_stock` | int | 新品初始库存 |
| `create_time` | datetime | - |
| `update_time` | datetime | - |

### 4.5 `pms_category`（菜品分类表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | bigint PK | 自增 |
| `name` | varchar(32) | 分类名 |
| `sort` | int | 排序 |
| `create_time` | datetime | - |

### 4.6 `ai_prediction_record`（AI备菜预测记录表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | bigint PK | 自增 |
| `predict_date` | date | 预测目标日期 |
| `dish_id` | bigint | 菜品ID |
| `base_quantity` | int | 时序基础预测 |
| `ai_suggest_quantity` | int | AI建议量 |
| `final_quantity` | int | 人工确认量 |
| `reasoning` | text | AI推理过程 |
| `confidence` | decimal(3,2) | AI置信度 |
| `recent_avg_score` | decimal(2,1) | 近30天评分均值 |
| `status` | tinyint | 0-待确认 / 1-已确认 |
| `confirmed_by` | bigint | 确认人 |
| `create_time` | datetime | - |

### 4.7 `ai_knowledge_document`（RAG文档元数据表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | bigint PK | 自增 |
| `file_name` | varchar(128) | 原始文件名 |
| `file_url` | varchar(256) | 文件路径 |
| `chunk_count` | int | 分块数 |
| `version` | int | 版本号 |
| `status` | tinyint | draft/active/archived |
| `effective_from` | datetime | 生效时间 |
| `create_time` | datetime | - |

### 4.8 `inv_stock_log`（库存变更流水表）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | bigint PK | 自增 |
| `dish_id` | bigint | 菜品ID |
| `change_type` | varchar(16) | RESERVE/DEDUCT/ROLLBACK/MANUAL |
| `change_qty` | int | 变更量 |
| `before_qty` | int | 变更前 |
| `after_qty` | int | 变更后 |
| `order_no` | varchar(32) | 关联订单号 |
| `create_time` | datetime | - |

---

## 五、Java / Python 服务边界与接口清单

### 5.1 Java 后端接口

| 模块 | 接口 | 说明 |
|------|------|------|
| 用户 | `/api/auth/login` | 登录，返回JWT |
| 菜品 | `/api/dish/list` | 菜品列表（分类/库存/价格） |
| 订单 | `/api/order/submit` | 下单（Redis Lua预扣库存） |
| 订单 | `/api/order/{id}/add-dish` | 加菜（追加明细、扣库存、若SERVED回退ORDERED） |
| 订单 | `/api/order/{id}/pay` | 结账支付（状态→PAID，通知看板） |
| 订单 | `/api/order/my-list` | 顾客查自己订单 |
| 订单 | `/api/order/my-detail/{id}` | 顾客查订单详情（含按钮状态） |
| 订单 | `/api/order/admin-list` | 管理端订单列表 |
| 订单 | `/api/order/{id}/cancel` | 管理员撤销（ORDERED/SERVED→CANCELLED） |
| 厨房看板 | `/api/order/{id}/complete` | 厨房看板完成（ORDERED→SERVED） |
| 厨房看板 | `/api/kitchen-board/orders` | 当前ORDERED订单HTTP快照（重连用） |
| 评价 | `/api/review/submit` | 提交评价 |
| 菜品管理 | `/api/admin/dish/**` | CRUD |
| 库存管理 | `/api/admin/stock/**` | 查看/修改库存 |
| 知识库 | `/api/admin/knowledge/upload` | 上传文档（调Python处理） |
| WebSocket | `/ws/kitchen-board` | 厨房看板实时推送 |

### 5.2 Python 后端接口

| 模块 | 接口 | 说明 |
|------|------|------|
| AI客服 | `/ai/chat` | SSE流式对话（Agent + Function Calling） |
| 备菜预测 | `/ai/predict/trigger` | 触发LangGraph预测 |
| 备菜预测 | `/ai/predict/result?date=` | 查询预测结果 |
| 知识库 | `/ai/knowledge/process` | 文档向量化存入Milvus |
| 知识库 | `/ai/knowledge/search` | RAG检索 |

### 5.3 调用关系图

```
C端小程序/H5 ──HTTP──▶ Java (Spring Boot)
         │                │
         │                ├── HTTP ──▶ Python (FastAPI) /ai/chat
         │                │           Python (FastAPI) /ai/knowledge/*
         │                │
         │                ├── WebSocket ◀──▶ 厨房看板页面
         │                ├── Redis
         │                ├── MySQL
         │                └── RabbitMQ

管理端PC ──HTTP──▶ Java (Spring Boot)
         │                │
         │                ├── HTTP ──▶ Python (FastAPI) /ai/predict/*
         │                │           Python (FastAPI) /ai/knowledge/process
         │                │
         │                └── Milvus

Python (FastAPI) ──▶ MySQL (销量/评价查询)
                ──▶ Milvus (向量检索与写入)
                ──▶ 第三方API (天气)
```

---

## 六、非功能性需求

### 6.1 性能

| 指标 | 目标 |
|------|------|
| 下单/加菜接口 QPS | ≥ 500 |
| 核心接口 RT | < 200ms |
| AI客服 SSE 首字 | < 1s |
| 厨房看板推送延迟 | < 1s |

### 6.2 数据一致性

- 支付幂等：`payment_trade_no` 唯一索引。
- 状态更新：乐观锁（`WHERE status = ?`）。
- Redis/MySQL库存：定时对账 + MQ补偿。
- 库存回滚幂等：`inv_stock_log` 流水 + reservation token。

### 6.3 安全

- 全接口 JWT 鉴权。
- 顾客端 SQL 必须带 `user_id`。
- 管理端接口仅限管理员身份访问。

### 6.4 AI成本控制

- 客服：热点问题全量缓存（Redis，10 分钟 TTL）。
- 备菜：仅 Top N 重点菜走 LLM，其余用时序预测。
- RAG：检索截断 Top-3。

### 6.5 可观测性

| 指标 | 用途 |
|------|------|
| 下单/加菜接口耗时 | 性能监控 |
| Redis Lua 扣减成功率 | 库存异常告警 |
| AI客服 SSE 首字时间 | 体验监控 |
| 备菜预测 LLM 成功率/降级次数 | AI链路稳定 |
| RAG 检索命中率 | 知识库质量 |

---

## 七、风险清单

| # | 风险 | 严重 | 缓解 |
|---|------|------|------|
| 1 | SERVED状态下并发加菜与撤销 | 高 | 乐观锁（`WHERE status=?`），先到先得 |
| 2 | 库存预扣回滚不一致 | 高 | 流水表 + reservation token + MQ去重 |
| 3 | 厨房看板WebSocket断线漏消息 | 中 | 重连后HTTP快照补齐 + event_version去重 |
| 4 | 顾客ORDERED状态结账，厨房正在做菜 | 中 | 结账后WebSocket通知看板卡片消失，厨师停止制作 |
| 5 | LLM JSON格式不稳定 | 中 | Schema校验 + 一次重试 + 降级时序预测 |
| 6 | 新品/活动日预测失真 | 中 | 新品初始库存 + 管理员手动系数 |
| 7 | RAG阈值不通用 | 中 | 阈值可配置 + 监控调优 |
| 8 | 知识库文档版本碎片化 | 低 | 版本管理 + 向量库按版本隔离 |

---

## 八、附录：面试叙述逻辑建议

**项目一句话介绍**：
「我做了一个餐厅的先做后付点单系统，Java负责交易链路的高并发库存扣减，Python负责LangGraph多节点AI备菜预测和Agent客服。顾客下单→厨房看板出菜→结账支付，流程完整闭环，评价数据还能回流优化AI预测。」

**技术亮点 4 个**：
1. **Redis + Lua 原子库存扣减**：下单和加菜都走原子操作，防超卖。
2. **LangGraph 多节点预测**：销量、天气、节假日、评价四维输入，每个节点可独立降级。
3. **评价反馈闭环**：顾客评价 → 预测输入 → 调整备菜量。
4. **厨房看板 WebSocket 实时推送**：新订单、加菜、结账、撤销四种事件，卡片实时刷新。
