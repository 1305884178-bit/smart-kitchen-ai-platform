# 智慧后厨备菜与点单系统 - 开发计划 (Development Plan)

> 本文档用于管理项目的迭代节奏，将大目标拆解为可执行的 Sprint，方便跟踪进度。

## 🎯 当前整体进度：Phase 5 已完成，准备进入 Phase 6（前端展示层）

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

## ⚪ Phase 6: 前端展示层
**目标**：B端 PC Web + C端微信小程序，打通全部后端接口实现可视化交互。

**B端技术栈**：Vue 3 + Element Plus + Axios + Pinia + Vue Router
**C端技术栈**：微信小程序原生框架（WXML/WXSS/JS）

> **注意**：在启动前端开发前，必须先完成 Step 0 后端 API 补齐，否则部分页面会因接口返回 null 而阻塞。

### Step 0: 后端 API 补齐（前端前置依赖）
*   [x] `/api/order/{id}/add-dish` — 加菜逻辑：扣库存 + 追加明细 + SERVED→ORDERED 状态回退
*   [x] `/api/order/my-list` — 顾客历史订单分页查询（支持 page/size）
*   [x] `/api/order/my-detail/{id}` — 订单详情，含 availableActions（ADD_DISH/PAY/REVIEW）字段
*   [x] `/api/order/admin-list` — 管理端全量订单分页 + 按 status/seatNumber 筛选
*   [x] `/api/order/{id}/complete` — 厨房出餐完成（从 ORDERED→SERVED），复用 OrderService.serveOrder()
*   [x] `/api/admin/stock/view/{dishId}` — 返回结构增强：补充 dishName、dailyStock、alertThreshold，与流水列表一并返回
*   [x] `/api/admin/review/list` — 补充按评分（score）筛选参数
*   [x] `/api/admin/dashboard/stats` — **新增**：今日订单数、今日营收、待出餐数量、库存预警菜品数（供仪表盘首页使用）
*   [x] `/api/admin/upload/sts-token` — **新增**：阿里云 OSS STS 临时凭证签发，供前端直传图片（AccessKey 不泄露到前端）

### Step 1: B端基础设施搭建（PC Web，Vue 3）
*   [ ] **项目脚手架**：Vue 3 工程初始化（如 `npm create vite@latest admin-web -- --template vue`）。
*   [ ] **Axios 封装**：请求拦截器（自动注入 `Authorization: Bearer <token>`）+ 响应拦截器（401→清除token→跳转登录，统一错误提示）。
*   [ ] **devServer 代理配置**：`vite.config.ts` 中配置 proxy，开发环境代理 `/api` 和 `/ws` 到后端。
*   [ ] **环境变量**：`.env.development` / `.env.production` 配置 `VITE_API_BASE_URL`、`VITE_WS_URL`。
*   [ ] **Vue Router 路由与权限守卫**：`router.beforeEach` 校验登录态，仅 ADMIN 角色可访问，无 token 跳转登录页。
*   [ ] **WebSocket 客户端封装**：心跳保活 + 断线自动重连 + 重连后回调 HTTP 快照补齐。连接时 URL 携带 `?token=xxx` 参数，后端握手阶段校验（复用 JwtInterceptor 逻辑）。

### Step 2: B端管理后台页面（PC Web）
*   [ ] **全局布局**：左侧可折叠侧边栏菜单 + 顶部面包屑导航 + 右侧主内容区，根据路由自动展开高亮。
*   [ ] **登录页** `/admin/login`：账密登录 → JWT 存储 → 路由守卫跳转。
*   [ ] **仪表盘首页** `/admin/dashboard`：四张统计卡片（今日订单数/今日营收/待出餐数量/库存预警菜品数）+ 调用 `/api/admin/dashboard/stats`。
*   [ ] **厨房看板** `/admin/kitchen-board`：WebSocket 实时卡片展示（订单号/座位/菜品列表/时间）+ [完成出餐]按钮 + 断线重连 HTTP 快照补齐。
*   [ ] **订单管理** `/admin/orders`：全量列表 + 状态筛选 + [撤销] [完成出餐] 操作 + 订单详情弹窗（菜品明细/状态时间轴：下单→出餐→结账）。
*   [ ] **菜品管理** `/admin/dishes`：CRUD 表单 + 上下架切换 + 图片上传（el-upload 获取 STS Token → 直传阿里云 OSS → 前端即时回显 → 提交时将 OSS URL 写入 `pms_dish.image` 存入数据库）+ 分类管理（CRUD 子模块或内联弹窗）。
*   [ ] **库存管理** `/admin/stock`：菜品库存列表 + 手动调整库存 + 变更流水日志表格（变更类型/数量/前后值/时间）。
*   [ ] **AI 备菜预测** `/admin/predict`：触发预测按钮 + 结果表格（dishName/baseQuantity/aiSuggestQuantity/confidence/reasoning）+ confidence<0.4 行红色高亮标记 + 人工确认/覆盖输入。
*   [ ] **AI 知识库** `/admin/knowledge`：文档上传（Multipart）+ 列表展示（文件名/版本号/状态标签 draft|active|archived/分块数/创建时间）。
*   [ ] **评价管理** `/admin/review`：评价列表表格（订单号/评分/内容/时间），按评分筛选。

### Step 3: C端微信小程序搭建
*   [ ] **小程序项目初始化**：注册小程序 AppID，配置服务器域名白名单（request/uploadFile/socket 合法域名）。
*   [ ] **网络请求封装**：封装 `wx.request` 为 Promise，统一注入 `Authorization: Bearer <token>`，处理 401 → 清除 token → 重新登录。
*   [ ] **Token 持久化**：`wx.setStorageSync('token', token)` 存储登录态，小程序启动时读取并校验。
*   [ ] **SSE 客户端实现**：小程序不支持原生 EventSource，需用 `wx.request` 配合 `enableChunked: true` 实现流式读取（AI 客服）；低版本基础库不兼容时降级为普通 POST 等待完整响应。

### Step 4: C端微信小程序页面
*   [ ] **全局布局**：底部 TabBar（菜单 / 订单 / 我的），`app.json` 配置 tabBar 与页面路由。
*   [ ] **微信登录**：`wx.login()` 获取 code → `wx.request` 调 `/api/auth/wx-login` → 新用户跳注册页，老用户存 token 进入首页。
*   [ ] **用户注册页** `pages/register/index`：昵称/头像/手机号填写 → 调 `/api/auth/register` → 获取 token → 进入首页。
*   [ ] **菜品浏览** `pages/menu/index`：分类 Tab 筛选 + 菜品卡片（图片/名称/价格/库存状态）+ 加入购物车按钮，购物车角标数量。
*   [ ] **菜品详情** `pages/dish-detail/index`：点击菜品卡片进入，展示大图/配料/过敏原信息/已有评价。
*   [ ] **购物车状态管理**：使用 `wx.setStorageSync` 持久化购物车数据（菜品列表/数量/总金额），下单前调后端校验接口确保价格/库存/状态为最新。
*   [ ] **选座下单** `pages/order/index`：可视化座位图（A01-A05, B01-B05 两排网格，已占用灰色不可选）+ 已选菜品确认列表 + 提交订单。
*   [ ] **订单详情** `pages/order-detail/index`：订单状态流展示（ORDERED→SERVED→PAID）+ [加菜] [结账] [评价] 按钮（按状态显示可用操作）。状态刷新优先 WebSocket 推送，降级为 30s 轮询。
*   [ ] **评价页** `pages/review/index`：星级评分组件 + 文字评价输入框 → 调 `/api/review/submit`，仅 PAID 状态可评价。
*   [ ] **我的订单** `pages/my-orders/index`：历史订单卡片列表（分页加载），点击跳转订单详情。
*   [ ] **AI 客服页** `pages/ai-chat/index`：对话列表 + 输入框 + SSE 流式接收 AI 回复（纯文本渲染，不输出 Markdown 表格以适配移动端）。

---

## ⚪ Phase 7: 优化、测试与 Docker 部署 (收尾)
**目标**：为面试展示做最后的包装和打磨，统一容器化上线。
*   [ ] 补充缺失的核心业务单元测试。
*   [ ] 梳理 API 文档（Swagger/Knife4j）。
*   [ ] 前端打包构建，配置 Nginx 反向代理。
*   [ ] **Docker 统一部署**：编写 docker-compose，将 MySQL、Redis、Spring Boot、Python FastAPI、Vue 前端（Nginx）全部容器化。
*   [ ] 简历亮点提炼与话术准备。