# 智慧后厨备菜与点单系统 开发计划

> 本文档记录项目的迭代阶段划分与各阶段交付内容，用于跟踪进度。
> 当前整体进度：**Phase 1 ~ Phase 6 已全部完成；Phase 7 进行中（核心测试已完成，Docker 统一容器化部署待完成）**。

---

## Phase 1：基础设施与项目骨架（已完成）

**目标**：搭建工程，配置数据库，跑通核心基础设施链路。

- 初始化 Spring Boot 工程（Java 21 + Spring Boot 3.2），集成 MyBatis-Plus、MySQL。
- 编写 `Result` 统一响应类与全局异常处理器。
- Category 模块：完成分类 CRUD，确立 Controller → Service → Mapper 标准分层。
- Auth 模块：设计 `sys_user` 表，实现 JWT 拦截器鉴权 + `UserContext`（ThreadLocal），提供微信 `wx-login` 模拟登录与 PC 端账密登录。
- Seat 模块：实现动态座位占用逻辑（固定 10 座，无需座位表，按 ORDERED/SERVED 订单反推占用），完成 `/api/seat/available` 接口。
- 编写了核心模块的单元/集成测试。

## Phase 2：C 端核心点单链路（已完成）

**目标**：顾客浏览菜品、提交订单、完成模拟结账。

- Dish 模块：按分类查询上架菜品列表，返回库存与配料信息。
- Order 模块（下单核心）：
  - 实现 `/api/order/submit`；
  - 引入 Redis，编写 Lua 脚本（`deduct_stock.lua`：key 缺失时用传入初值原子初始化再扣 / `return_stock.lua`：key 缺失时写回返还后的 DB 库存）实现高并发下的库存原子扣减；
  - 订单落库并初始化状态为 `ORDERED`，MySQL 库存条件更新作为第二道防线；
  - 已下架菜品处理：不计价、不扣库存、明细名称标记「（已下架）」。
- Order 模块（订单流转）：
  - 「确认结账」`/api/order/{id}/pay`：登记模拟流水号（`SIM_`+时间戳）与支付时间，采用「先登记、出餐后自动流转 PAID」模式；
  - 「撤销订单」：ORDERED 状态撤销返还库存（先 MySQL 原子加回，再 Redis 同步；SERVED 不返还）。

## Phase 3：B 端后厨协作链路（已完成）

**目标**：厨房实时看到订单并操作出餐，与 C 端形成状态闭环。

- KitchenBoard 模块：`/api/kitchen-board/orders` 返回全部 ORDERED 订单快照（时间升序）。
- WebSocket 实时推送：
  - `KitchenBoardWebSocketHandler`（`/ws/kitchen-board`，单会话广播）：下单时推送 `NEW_ORDER`；
  - `CustomerWebSocketHandler`（`/ws/customer`，按 userId 定向）：出餐时推送 `ORDER_SERVED` 通知顾客；
  - 在 OrderService 的下单、出餐节点埋点触发推送，推送失败不影响交易主流程。
- 厨房出餐：`/api/kitchen-board/order/{id}/serve`，ORDERED → SERVED（若顾客已提前登记支付则直接 PAID），记录 `complete_time`。

## Phase 4：B 端管理与高级功能（已完成）

**目标**：完善后台管理能力。

- AdminDish 模块：菜品增删改查、上下架、配料/过敏原/新品初始库存维护、图片上传（后端代理转存 OSS）。
- Category 管理：分类增删改查。
- Stock 模块：库存查看（含预警阈值）、手动调整、变更流水记录（`inv_stock_log`）。
- Review 模块：顾客对 PAID 订单提交评价（一单一评），后台评价列表按评分筛选。
- Dashboard 模块：`/api/admin/dashboard/stats` 聚合今日订单数、今日营收、待出餐数、库存预警数。

## Phase 5：AI 智能服务（Python 端，已完成）

**目标**：部署 FastAPI 服务，实现 LangGraph 备菜预测、AI 客服、RAG 知识库三大 AI 能力。

- **Step 1 基础设施**：搭建 FastAPI 工程（LangGraph / PyMySQL / redis-py / httpx）；搭建 Milvus Lite 本地向量库（`data/milvus.db`，集合 `kitchen_knowledge`，1024 维）；MySQL 建 `ai_prediction_record`、`ai_knowledge_document` 表。
- **Step 2 RAG 知识库**：`RecursiveCharacterTextSplitter(chunk_size=500, overlap=50)` 分块 → 百炼 Embedding → Milvus；版本管理机制（version/status/effective_from，检索按版本过滤）；暴露 `/ai/knowledge/process`、`/ai/knowledge/search`。
- **Step 3 AI 客服（ReAct Agent + Function Calling）**：挂载 3 个工具——`search_dish_by_preference`（Milvus 语义推荐）、`check_dish_inventory` 与 `get_dish_ingredients`（回调 Java 代理接口查 MySQL）；`/ai/chat` SSE 流式输出（`data: {content}` + `[DONE]`）；语义缓存替代原 MD5 精确缓存——问题转 embedding 后在 Milvus 集合 `ai_chat_semantic_cache` 检索，余弦相似度 ≥0.92 命中历史回答（近义问法可命中），缓存条目带知识库版本指纹（`ai_knowledge_document` 表哈希；Java 落库成功后主动 DEL Redis 指纹，60s TTL 作兜底）与 7 天保鲜期；仅缓存非动态回答（监听 `on_tool_start`，调用库存/配料等实时工具的回答不写入），embedding/Milvus 异常静默降级为直调 LLM。
- **Step 4 AI 备菜预测（LangGraph 多节点工作流）**：
  - 单工作流 `predict_subgraph`，由触发接口/定时任务直接调用，无 Supervisor 路由层；
  - 图结构：4 个数据采集节点并行（`get_sales_30d`、`get_tomorrow_weather`、`get_holiday_info`、`get_recent_reviews`）→ `time_series_predict` → `llm_adjust` → `save_result`；
  - 天气：OpenWeatherMap 5 日预报（3 小时间隔聚合为日级）；节假日：ModelScope MCP 服务（标准 JSON-RPC：initialize → tools/call）；
  - LLM Prompt 外置 `app/prompts/predict_llm_prompt.txt`；
  - 降级策略：外部 API 不可用跳过该维度；LLM 超时（60s）/异常降级为时序结果；冷启动五级降级链（30 天统计 → 近 N 天均值 → 同类菜均值 → 新品初始库存 → 日常库存 → 兜底 20）；
  - 触发：APScheduler Cron 每日 02:00 + 手动触发（异步任务 + Redis 进度查询）；暴露 `/ai/predict/trigger`、`/ai/predict/status`、`/ai/predict/result`、`/ai/predict/confirm`。
- **Step 5 Java 端代理集成**：
  - Python 客服工具的库存/配料查询统一走 Java 代理接口 `/api/proxy/dish/**`，数据层归属统一；
  - B 端预测代理 `/api/admin/predict/{trigger,result,status,confirm}`；
  - B 端知识库代理 `/api/admin/knowledge/{list,parse-file,upload}`（Word/PDF 由 Java 端 POI/PDFBox 解析为文本）；
  - Java 通过 RestTemplate 调用 Python，服务地址由 `smart-kitchen.python-service.url` 配置。

## Phase 6：前端展示层（已完成）

**目标**：B 端 PC Web + C 端微信小程序，打通全部后端接口。

**技术栈**：B 端 Vue 3 + Vite + Element Plus + Pinia + Vue Router + Axios；C 端微信原生小程序。

### Step 0：后端 API 补齐（前端前置依赖）

- `/api/order/{id}/add-dish` 加菜（**实现为父子订单模型**：新建子订单、独立扣库存、独立看板卡片）；
- `/api/order/my-list`、`/api/order/my-detail/{id}`（父子订单合并视图 + `availableActions` 按钮下发）；
- `/api/order/admin-list`、`/api/order/admin-detail/{id}`、`/api/order/{id}/complete`；
- `/api/admin/stock/view/{dishId}`（库存 + 流水）、`/api/admin/review/list`（评分筛选）；
- `/api/admin/dashboard/stats`（仪表盘聚合）；
- `/api/admin/upload/image`（图片代理上传 OSS）与 `/api/admin/upload/sts-token`（STS 凭证，预留）；
- `/api/auth/check-token`（小程序启动登录态校验）。

### Step 1：B 端基础设施

- Vite + Vue 3 脚手架；Axios 封装（请求注入 Bearer Token，401 清 token 跳登录，统一错误提示）；
- `vite.config.js` 开发代理 `/api`、`/ws`；`.env.development/.production` 环境变量；
- 路由守卫：未登录跳登录页，仅 ADMIN 角色可访问；
- WebSocket 客户端封装：30s 心跳、断线 3s 重连（最多 10 次）、重连后回调拉取 HTTP 快照补齐。

### Step 2：B 端管理后台页面

- 全局布局：可折叠侧边栏 + 面包屑 + 主内容区；
- 登录页 `/login`；仪表盘 `/dashboard`（四张统计卡片）；
- 厨房看板 `/kitchen-board`：WS 实时卡片 + 完成出餐 + 断线快照补齐；
- 订单管理 `/orders`：筛选分页 + 详情弹窗 + 撤销/完成出餐；
- 菜品管理 `/dishes`：CRUD + 上下架 + 图片上传回显 + 分类管理内联操作；
- 库存管理 `/stock`：库存列表（预警高亮）+ 手动调整 + 流水表格；
- AI 备菜预测 `/predict`：触发预测 → 3s 轮询任务状态 → 结果表格（低置信度高亮）→ 人工确认/覆盖；
- AI 知识库 `/knowledge`：文本录入 / Word、PDF 解析填充 → 上传 → 文档列表（版本/状态/分块数）；
- 评价管理 `/review`：只读列表 + 评分筛选。

### Step 3：C 端小程序基础设施

- 小程序工程初始化（AppID、基础库 3.3.4）；
- `wx.request` Promise 封装：统一注入 Bearer Token，401 清除登录态并触发静默重登；
- Token 持久化：`wx.Storage` 存储，启动时 `check-token` 校验；
- SSE 客户端：`wx.request` + `enableChunked: true` 分块接收（低版本基础库降级普通 POST）。

### Step 4：C 端小程序页面

- 底部 TabBar：菜单 / 订单 / AI 客服；
- 登录注册：`wx.login` → `wx-login`（新用户跳注册页）→ `register` 绑定昵称/头像/手机号；
- 菜单页：分类 Tab + 菜品卡片 + 购物车弹层（本地存储，7 个方法）+ 加菜模式（借 `globalData` 跨 Tab 传参）；
- 菜品详情页：大图/配料/过敏原/评价，库存为 0 禁加购；
- 确认订单页：座位选择（占用置灰）+ 下架菜品灰显不计价 + 备注 + 提交；
- 订单详情页：父子订单合并展示 + 按 `availableActions` 动态渲染加菜/结账/评价按钮；
- 我的订单：分页列表 + 上拉加载；评价页：五星 + 文字；
- AI 客服页：对话列表 + 流式打字机渲染，直连 Python `aiBase`。

### Step 5：RabbitMQ 订单超时自动取消

- `RabbitMQConfig`：声明延迟交换机/队列（`order.delay.*`，TTL 30 分钟 + 死信路由）、超时交换机/队列（`order.timeout.*`）、异常兜底 DLX/DLQ 及绑定；
- `OrderTimeoutConsumer`：监听超时队列，仅 ORDERED/SERVED 执行取消（含库存返还与级联子订单），其余状态跳过，手动 ACK；
- `OrderServiceImpl.submitOrder()` 落库成功后发送延迟消息（消息体仅 orderId）；
- 单元测试覆盖：正常超时取消、已支付跳过、已撤销跳过、订单不存在、消费异常仍 ACK。

---

## Phase 7：测试完善与 Docker 统一部署（进行中）

**目标**：补齐质量保障，完成统一容器化交付。

- [x] 核心业务测试：Java 侧 17 个测试类（12 个 Controller 集成测试基于 H2 内存库 + 分类服务与超时消费者单元测试 + 订单并发/回滚/WS 鉴权测试）；Python 侧 MCP 工具/预测/RAG 测试脚本；小程序静态结构校验脚本。
- [x] 并发与安全加固：
  - 订单状态迁移乐观锁下沉——`payOrder`/`cancelOrder`/`serveOrder` 改为 SQL 条件更新（`WHERE id=? AND status IN (...) [AND pay_time IS NULL]`），影响行数 0 即并发冲突报错，堵住先查后改窗口（`OrderServiceConcurrencyTest`：20 线程并发支付/出餐/支付 vs 撤销竞争，断言恰好一笔生效）；
  - 下单中途失败补偿测试（`OrderServiceSubmitRollbackTest`：mock `deductStock` 返回 0，断言订单不落库且 Redis 回滚脚本执行；ARGV 含初值、无 hasKey 预热）；
  - 库存缓存加固：Lua 内原子初始化缺失 key；返还时 key 缺失写回 DB 真值；MySQL 返还改原子 `daily_stock = daily_stock + qty`；知识库落库后 DEL `kb_version_fingerprint`；
  - 接口级压测（`tests/load_order_submit.py`：50 线程并发抢库存 20，实测 20 成功 30 拒绝、Redis/MySQL 双侧库存恰好为 0）；
  - WebSocket 握手 JWT 鉴权（`WebSocketAuthInterceptor`：`/ws/kitchen-board` 需 ADMIN，`/ws/customer` 的 userId 改为 token 解析，`WebSocketAuthInterceptorTest` 6 项断言）；
  - **认证加固**：BCrypt 密码哈希；HTTP 拦截器对管理端接口强制 ADMIN（403）；Access（2h）+ Refresh（7d）双 Token、Redis 黑名单与刷新旋转、全端吊销；管理端/小程序 401 时静默 refresh；`AuthControllerIntegrationTest` 6 项 + `PasswordEncoderTest` 全过（Java 合计 71 项）。
- [ ] API 文档梳理（Swagger / Knife4j）。
- [ ] 前端生产构建与 Nginx 配置（admin-web 静态托管 + `/api`、`/ws` 反向代理）。
- [ ] **Docker 统一部署（待完成）**：编写各服务 Dockerfile 与根目录 `docker-compose.yml`，将 MySQL、Redis、RabbitMQ、Java 后端、Python AI 服务、admin-web（Nginx）统一容器化编排；Milvus Lite 数据文件挂载持久卷；密钥经 `.env` 注入。当前仅有 `smart-kitchen-ai/Dockerfile` 占位空文件。
