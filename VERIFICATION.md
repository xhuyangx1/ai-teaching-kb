# VERIFICATION.md — 全链路真机验证报告（t7）

> 验证人：impl-glm ｜ 日期：2026-10-03 ｜ 环境：本会话沙箱（受限令牌）
> 对象：t4（骨架+RAG+数据层）/ t5（MQ 异步+限流幂等）/ t6（插件+聊天导入+压测脚本）交付物

## 0. 结论速览

| # | 验收项 | 结果 |
|---|---|---|
| V1 | mvn package 退出码 0 | **passed** |
| V2 | java -jar 启动成功，启动日志无 ERROR | **passed** |
| V3 | MD/TXT/DOCX 上传 → PENDING → SUCCESS | **passed** |
| V4 | chat 引用溯源 / 插件触发 / MD5 幂等 | **passed** |
| V5 | 限流 429（用户维度 + 伪造 XFF 场景 IP 维度兜底） | **passed** |
| V6 | 每条验收项 passed/failed 与证据齐全，failed 如实移交 | **passed**（0 条 failed，2 条行为边界如实记录） |

**failed 项：无。** 行为边界（非缺陷，移交 t8 裁决）见 §6。

---

## 1. 沙箱约束声明（必读：沙箱内结论 ≠ 用户环境预期）

本会话运行环境是**受限令牌**（Low Integrity Level + Mandatory Label NW）：

- `java.nio.Files.isWritable()` 对任意文件返回 false（实际写入成功）。H2 `Engine.openSession`
  读到 false 会强制 `ACCESS_MODE_DATA=r`（覆盖 URL 显式 rw）。
- 表现：**同一数据库文件的"首轮启动"写入正常；重启后对已有库的写入抛 SQLState 90097
  "The database is read only"**（读不受影响）。这是**环境约束，不是实现缺陷**。
- `H2StorageDiagnostic` 已在每次启动日志中打印该情形与处置方式（本轮运行日志均含
  "检测到受限运行环境…" 提示，佐证代码兜底生效）。
- 因此本报告所有 H2 写入断言均在 **删除 data/kb.mv.db 后的"单次 JVM 进程"** 内完成；
  **未做跨重启持久化写验证**。用户在普通（非沙箱）终端运行 run.cmd 时，写入与跨重启保留
  均预期正常（t4 已在普通会话验证过首轮写入；此差异由上述机制唯一解释）。

---

## 2. V1 构建验证 — passed

命令（复用上游 verify 命令）：

    tools\apache-maven-3.9.9\bin\mvn.cmd -B -s tools\settings.xml -f ai-teaching-kb\pom.xml package -DskipTests

- 结果：**BUILD SUCCESS，MVN_EXIT=0**（Total time 3.124s）
- 产物：target/ai-teaching-kb-1.0.0.jar，**122,544,205 字节**（spring-boot repackage fat jar）

## 3. V2 启动验证 — passed

- 命令：`java -Dfile.encoding=UTF-8 -jar target/ai-teaching-kb-1.0.0.jar`（工作目录 ai-teaching-kb/）
- `GET /api/health` → **HTTP 200**，70s 内 READY=True
- 启动日志（data/t7-run.log）关键行：
  - `Started KbApplication in 7.279 seconds`
  - `插件注册完成，共 3 个工具: [calculator, datetime, knowledge_search]`（t6）
  - `消息模式: memory（内存队列…）`、`内存队列已就绪 workers=4`（t5）
  - `Redis 状态: 可用（限流/会话记忆启用）`
  - **ERROR 行数：0**

## 4. V3 导入链路（MD/TXT/DOCX 各一）— passed

单次 JVM 进程内，上传 3 个测试件（userId=t7tester）：

| 文件 | HTTP | 响应 | 任务终态 |
|---|---|---|---|
| test-v7.md | 200 | docId=d_5525…、taskId=t_b465…、taskStatus=PENDING | **SUCCESS** progress=100 |
| test-v7.txt | 200 | docId=d_5b21…、taskId=t_c190…、taskStatus=PENDING | **SUCCESS** progress=100 |
| test-v7.docx（自建 OpenXML 最小包，POI 解析） | 200 | docId=d_3204…、taskId=t_b69d…、taskStatus=PENDING | **SUCCESS** progress=100 |

日志留证（data/t7-run.log）：

    DocServiceImpl : 文档导入完成 docId=d_5525f28da9374b7b9214 chunks=1
    DocServiceImpl : 文档导入完成 docId=d_5b210c77931a4401a469 chunks=1
    DocServiceImpl : 文档导入完成 docId=d_3204d3b0037f48cd80aa chunks=1

异步语义同时验证：3 个上传响应均立即返回 PENDING（t5 的 afterCommit 投递修复生效，任务未被消费者丢弃）。

## 5. V4 问答 / 溯源 / 插件 / 幂等 — passed

单次 JVM 进程内（重新建库，userId=t7b/t7c）：

### 5.1 引用溯源
- 上传 test-v7.md → 任务 SUCCESS 后 `POST /api/chat {"question":"文本检索为什么用余弦相似度？"}`
- **HTTP 200，citations=1**：cite0 = docId/chunkId（与上传文档一致）、score=0.4498
- answer 含资料抽取句与 `[1]` 引用标注（mock-extractive 抽取式实现）

### 5.2 插件触发（mock provider，规则触发）
- `POST /api/chat {"question":"23*17+5 等于多少？请帮我计算一下"}`
- **HTTP 200，pluginCalls=1**：`tool=calculator, args={"expression":"23*17+5"}, result="23*17+5 = 396"`
- 判定基准：23*17+5 = **396**（TASKS.md 原 "406" 系任务书笔误，队长已修正）

### 5.3 MD5 幂等
- 同一文件第二次上传（同 userId）：
  - **HTTP 200，duplicate=true，sameDocId=true，sameTaskId=true**
  - chunks 数量 **1 → 1 不变**（未重复导入、未重复切分）

## 6. 行为边界记录（非缺陷，供 t8 裁决）

1. **answer 与 pluginCalls 分离**：mock provider 下，工具调用结果正确出现在结构化字段
   `pluginCalls[]`，但最终 answer 走资料抽取路径，不含计算结果文本。真实 openai 模式下
   模型会把工具结果编入 answer（结构不同属 mock 实现的展示边界）。t7 按 pluginCalls 判定
   插件触发，**不记 FAIL**；是否让 impl-ds 在 MockChatModel 第二轮把工具结果编入 answer，
   由 t8 coredev 裁决（建议倾向：体验优化而非缺陷）。
2. **限流顺序**：限流检查在会话记忆/检索之前，429 响应不产生 citations 等副作用（符合设计）。

## 7. V5 限流与运维观测 — passed

### 7.1 用户维度 429
- 同 userId 连发 15 次 `POST /api/chat`（capacity=10, qps=5 配置）：
  **codes = 200×10, 429×4, …**，count429=**4**（≥1 即达标，令牌以 5/s 回补，429 分布符合令牌桶语义）

### 7.2 伪造 X-Forwarded-For 场景（IP 维度兜底）
- 场景：150 个**并发**请求，每个使用**不同 userId**、**同一个伪造 XFF (9.9.9.9)**，
  HttpClient 并发压入（绕开顺序请求慢于 50 qps 回补的问题）：
  **count200=108，count429=42** —— 用户维度全部换号绕过的情况下，**IP 维度桶兜住了 42 次**，
  证明 IP 桶真实生效（ipCapacity=100 + 50 qps 回补下，150 并发烧穿桶余量）。
- 说明：顺序请求下（110 连发）IP 桶不触发属**正确行为**——令牌回补 50/s 快于单线程请求速率，
  桶永不耗尽；t5 报告的"20 次拦截"使用了更小桶容量的专用配置。安全语义（换 userId 不能绕过
  IP 桶）已由并发突发验证成立。
- 配置提醒（沿袭 t5 诚实边界）：`kb.rate-limit.trust-forwarded-for=true` 时 XFF 头被信任，
  直连场景应置 false（t9 计划改默认值）。

### 7.3 运维端点（t5 交付）
- `GET /api/ops/tasks/stats` → `{"SUCCESS":2}` ✅
- `GET /api/ops/tasks?status=FAILED` → count=0（本轮无失败任务）✅
- `GET /api/ops/mq` → mode=memory, consumed=2, idempotentSkipped=0, deadLetterCount=0,
  queueDepths={KB_TASK:0} ✅
- `GET /api/ops/rate-limit` → 200 ✅

### 7.4 聊天导入状态机专项复测（t6 自查缺陷回归）
- `POST /api/chats/import`（demo-chat.json 8 条）→ taskStatus=PENDING
- 轮询观测状态流转：**RUNNING → SUCCESS**，progress=100，**不卡 RUNNING**
  （t6 修复的 markSuccess 缺失已回归验证）
- 脱敏复测：chunks 中手机号原文零残留，`[REDACTED:PHONE]` 存在，speaker="张老师,小明,小红" ✅

### 7.5 错误路径
- `GET /api/tasks/t_nonexistent` → **HTTP 404** ✅

## 8. 验证覆盖边界（未验证项，如实声明）

1. **跨重启持久化**：沙箱受限令牌下无法验证（见 §1）；用户环境预期正常。
2. **RocketMQ 真机收发**：9876 未监听（t5 已验证开关选中与诚实失败，真机链路留用户自测）。
3. **MySQL profile 连通**：root 密码未知，仅交付配置（TASKS.md §8 既定边界）。
4. **压测数字（P99/TPS）**：留用户以 jmeter/qa-load.jmx 自测。
5. **kb.llm.provider=openai 真实调用**：无 API key（t4 已验证装配切换与健康检查回落）。

## 9. 证据文件清单

| 文件 | 内容 |
|---|---|
| ai-teaching-kb/data/t7-run.log | V2-V3 启动 + 三格式导入日志（无 ERROR） |
| ai-teaching-kb/data/t7-run3.log | V4/V5 会话日志（chat/插件/幂等/导入/限流） |
| ai-teaching-kb/data/t7-run4.log | XFF 顺序连发日志 |
| ai-teaching-kb/data/t7-run5.log | XFF 并发突发日志（42×429） |
| ai-teaching-kb/data/test-v7.{md,txt,docx} | 测试件（DOCX 为自建最小 OpenXML 包） |
| ai-teaching-kb/data/demo-chat.json | 脱敏测试件（8 条，含三类敏感样本） |
