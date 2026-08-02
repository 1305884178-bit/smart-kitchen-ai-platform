# 智慧后厨备菜与点单系统 - 开发计划 (Development Plan)

> 本文档用于管理项目的迭代节奏，将大目标拆解为可执行的 Sprint，方便跟踪进度。

## 🎯 当前整体进度：Phase 5 已完成，准备进入 Phase 6

---

## 🟢 Phase 1: 基础设施与项目骨架 (已完成)
**目标**：搭建工程，配置数据库，跑通核心的基础设施链路。
*   [x] 初始化 Spring Boot 工程，集成 MyBatis-Plus、MySQL。
*   [x] 编写 `Result` 统一响应类与全局异常处理器。
*   [x] **Category 模块**：完成分类的 CRUD（跑通 Controller -> Service -> Mapper 标准分层）。
*   [x] **Auth 模块**：设计 `sys_user` 表，实现基于 JWT 的拦截器鉴权，提供 `wx-login` 模拟逻辑与 PC 端账密登录。
*   [x] **Seat 模块**：实现动态座位占用逻辑（无需座位表，根据订单状态反推），完成 `/api/seat/available` 接口。
*   [x] 编写核心模块的单元/集成测试。

---

## 🟢 Phase 2: C端核心点单链路 (已完成)
**目标**：顾客从小程序进入，浏览菜品、加入购物车、提交订单、完成模拟结账。
*   [x] **Dish 模块 (菜品浏览)**
    *   [x] 实现按分类查询上架菜品列表接口。
    *   [x] 返回数据需包含菜品的基本信息、库存状态及规格配料。
*   [x] **Order 模块 (下单核心)**
    *   [x] 实现提交订单接口 (`/api/order/submit`)。
    *   [x] **重点**：引入 Redis，编写 Lua 脚本实现高并发下的库存安全扣减。
    *   [x] 实现订单的落库与状态初始化 (状态变更为 `ORDERED`)。
*   [x] **Order 模块 (订单流转)**
    *   [x] 实现「确认结账」接口 (`/api/order/{id}/pay`)：状态变更为 `PAID`，记录模拟流水号。
    *   [x] 实现「撤销订单」接口：退还库存。

---

## 🟢 Phase 3: B端后厨协作链路 (已完成)
**目标**：厨房能够实时看到订单，并操作出餐，与 C 端形成状态闭环。
*   [x] **KitchenBoard 模块 (HTTP快照)**
    *   实现查询所有状态为 `ORDERED` 的待出餐订单列表。
*   [x] **WebSocket 实时推送 (核心架构)**
    *   完善 `KitchenBoardWebSocketHandler`。
    *   在 OrderService 的关键节点（下单、结账、撤销）埋点，触发 WebSocket 推送给前端。
*   [x] **厨房出餐操作**
    *   实现后厨点击「完成」接口：修改订单状态为 `SERVED`，并触发推送通知 C 端。

---

## 🟢 Phase 4: B端管理与高级功能 (已完成)
**目标**：完善系统的后台管理能力，增加项目亮点。
*   [x] **AdminDish 模块 (菜品管理)**：实现菜品的增删改查、上下架、配料图文上传。
*   [x] **Stock 模块 (库存管理)**：实现库存盘点、入库/出库流水记录查询。
*   [x] **Review 模块 (餐后评价)**：实现顾客对 PAID 状态订单的评价提交，以及后台的评价列表查询。

---

## 🟢 Phase 5: AI 智能服务（Python 端，已完成）
**目标**：部署 Python FastAPI 服务，实现 LangGraph 备菜预测、AI 客服、RAG 知识库三大 AI 能力。

> **实现顺序建议**：基础设施与数据表准备 -> RAG 知识库 -> AI 客服 -> AI 备菜预测 -> Java 端代理集成。

*   [x] **Step 1: 基础设施与数据表准备**
    *   搭建 FastAPI 工程，集成 LangGraph 依赖。
    *   搭建 Milvus 向量数据库环境。
    *   在 MySQL 中执行 DDL，创建 `ai_prediction_record` 和 `ai_knowledge_document` 表。
*   [x] **Step 2: AI 知识库（RAG）**
    *   实现文档分块策略：`RecursiveCharacterTextSplitter(chunk_size=500, overlap=50)`。
    *   实现文档版本管理机制（支持 version/status/effective_from）。
    *   暴露 `/ai/knowledge/process`（文档向量化）和 `/ai/knowledge/search`（Milvus 向量检索）。
*   [x] **Step 3: AI 客服（单Agent + Function Calling）**
    *   挂载 3 个工具函数：`search_dish_by_preference` (RAG语义推荐)、`check_dish_inventory` (MySQL实时库存查询)、`get_dish_ingredients` (配料/过敏原查询)。
    *   暴露 `/ai/chat` SSE 流式接口，首字响应 < 1s，前端实时展示 AI 回答。
    *   实现问答结果 Redis 全量热点缓存（TTL 10分钟），降低 LLM 成本。
*   [x] **Step 4: AI 备菜预测（LangGraph Supervisor + 子图）**
    *   [x] 架构：Supervisor 多智能体入口 → 根据 task_type 路由到备菜预测子图（predict_agent），未知类型走 other_node 兜底返回不支持提示
    *   [x] 预测子图 7 个节点：get_sales_30d、get_tomorrow_weather、get_holiday_info、get_recent_reviews、time_series_predict、llm_adjust、save_result
    *   [x] LLM Prompt 外置到 `app/prompts/predict_llm_prompt.txt`，Supervisor 路由提示词外置到 `app/prompts/supervisor_prompt.txt`
    *   [x] 使用 **MCP 协议** 调用天气和节假日外部 API（天气: OpenWeatherMap 5日预报/3小时间隔聚合为日级数据; 节假日: ModelScope `china-festival-mcp` 通过 JSON-RPC 调用）
    *   [x] **完善降级策略**：外部 API 不可用时跳过；LLM 超时或 JSON 格式错误时降级为时序预测；冷启动（新菜品/新店无历史数据）逐级降级：同类菜品均值 → 新品初始库存 → 日常库存 → 绝对兜底
    *   [x] 实现触发机制：定时任务（Cron 每日 02:00 触发）与管理员手动触发
    *   [x] 暴露 `/ai/predict/trigger`、`/ai/predict/result`、`/ai/predict/confirm` 三个接口，支持管理员人工干预与覆盖预测量
*   [x] **Step 5: Java 端代理接口集成**
    *   **提醒**：将 `dish_tools.py` 中三步工具函数（`search_dish_by_preference` 除外，它走 Milvus RAG）的 pymysql 直连替换为调用 Java 代理接口，统一数据层归属。
    *   [x] Java 端新增菜品查询代理接口：供 Python `check_dish_inventory` 和 `get_dish_ingredients` 调用。
    *   [x] 实现 B 端备菜预测代理接口：`/api/admin/predict/trigger`、`/api/admin/predict/result`、`/api/admin/predict/confirm`。
    *   [x] 实现 B 端知识库上传代理接口：`/api/admin/knowledge/upload`。
    *   [x] Java 端通过 RestTemplate 调用 Python 接口。

---

## ⚪ Phase 6: 优化、测试与部署 (收尾)
**目标**：为面试展示做最后的包装和打磨。
*   [ ] 补充缺失的核心业务（如下单、并发扣库存）的单元测试。
*   [ ] 梳理 API 文档（可接入 Swagger/Knife4j）。
*   [ ] 准备演示环境：编写 Dockerfile，将 MySQL、Redis、Spring Boot 统一容器化部署（可选，本机演示也可）。
*   [ ] 简历亮点提炼与话术准备。