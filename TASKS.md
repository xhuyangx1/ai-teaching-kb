# 任务书：AI 教学知识库平台（ai-teaching-kb）

> 本文是下游 implementation 的**唯一施工依据**。技术选型与环境事实全部来自《团队输入-环境事实与范围.md》（2026-10-03 队长实测），范围来自《项目规格-AI教学知识库平台.md》v2 瘦身版，面试视角参考《追问链-100问.md》。
> 硬约束：**离线可跑（无 API key、无 Docker、无 PostgreSQL）、可打包（fat jar）、诚实降级（MQ/LLM 均有 mock 开关）**。

## 0. 环境与命令（实测事实，成员不得偏离）

- JDK 17.0.19（Temurin，已在 PATH）；Maven 3.9.9：`<ws>\tools\apache-maven-3.9.9\bin\mvn.cmd`，**必须 `-s <ws>\tools\settings.xml`**（本地仓库指向 tools\m2repo，mirror 走 aliyun）。禁止写 C:\Users\86178\.m2\repository。
- 已实测通过的组合（探针项目 _probe_boot）：Spring Boot 3.5.5 + web + H2 + rocketmq-client 5.3.0 + langchain4j-open-ai 1.16.3 + jedis 5.0.2 + mybatis-plus-boot3 3.5.9 + lombok；spring-boot-maven-plugin repackage 成功。
- Redis 3.0.504 本机 6379 运行中（**只当 KV 用**：无 MODULE/RediSearch）。MySQL 8.0 在 3306 但 **root 密码未知** → 数据层默认 H2，MySQL 只是可选 profile。RocketMQ 5.x 已装但 9876 未监听且安装目录不可写 → 运行时默认内存队列，真 RocketMQ 走配置开关。无任何 LLM API key → 默认 mock provider。
- PDFBox / POI 不在本地缓存，联网下载可接受；**springdoc、spring-ai 不可用，不得引入**。Docker / PostgreSQL / pgvector 一律禁止出现。
- 构建命令模板：`<ws>\tools\apache-maven-3.9.9\bin\mvn.cmd -B -s <ws>\tools\settings.xml package -DskipTests`（测试去掉 -DskipTests）。
- 项目坐标：groupId `com.aiteaching`，artifactId `ai-teaching-kb`，version `1.0.0`，主类 `com.aiteaching.kb.KbApplication`。

## 1. 范围（P0 必做 / P1 必做但简化 / 明确不做）

**P0**：文档导入（MD/TXT/DOCX）→ 切分 → 向量化 → 入库；问答接口（向量检索 → 拼 Prompt → 生成 → 引用溯源）；MQ 异步化 + 任务状态机；Redis 限流 + MD5 幂等；JMeter 压测脚本（可交付，数字用户自测）。
**P1**：插件轻量版（@Tool 注解 + 注册表 + Function Calling，3 个插件，不做 SPI/热插拔）；聊天记录导入轻量版（JSON → 规则切分 → 正则脱敏，不做增量游标/多格式/图片消息）。
**不做（写进 README 的"未实现与后续规划"）**：教学视频生成、SPI/热插拔、Rerank（用距离阈值+top-k 截断替代）、BM25+RRF（纯向量检索）、SSE 流式（同步返回）、Docker、多租户/权限体系/后台管理/插件市场。

## 2. 包结构与文件清单

```
ai-teaching-kb/
├─ pom.xml
├─ run.cmd                                  # 启动脚本：java -jar target\ai-teaching-kb-1.0.0.jar
├─ README.md                                # 面试官视角：快速开始/架构图/未实现清单
├─ 设计文档.md                               # 选型对比/时序/异常兜底（规格第六节大纲）
├─ jmeter/qa-load.jmx                       # 压测场景（问答接口 + 导入接口）
├─ jmeter/README.md                         # 场景、并发、样本量说明（数字留空用户自测）
├─ src/main/resources/
│  ├─ application.yml                       # 默认 H2 + mock LLM + memory MQ
│  ├─ application-mysql.yml                 # 可选 profile：MySQL 8（--spring.profiles.active=mysql）
│  ├─ schema-h2.sql                         # 见 §4 DDL（H2 语法）
│  ├─ schema-mysql.sql                      # 同构 DDL（MySQL 语法）
│  └─ data/demo-chat.json                   # 示例聊天记录（含手机号样本，供脱敏验证）
└─ src/main/java/com/aiteaching/kb/
   ├─ KbApplication.java
   ├─ common/    ApiResponse.java  BusinessException.java  GlobalExceptionHandler.java  TraceIdFilter.java
   ├─ controller/ HealthController.java  DocumentController.java  ChatController.java
   │             TaskController.java  ChatImportController.java  PluginController.java
   ├─ service/   DocService.java  RagService.java  ChatService.java  TaskService.java
   │             ChatImportService.java  （实现类放 service/impl/ 同名 Impl）
   ├─ rag/       splitter/DocSplitter.java  splitter/ChatSplitter.java
   │             embed/KbEmbeddingModel.java  embed/MockEmbeddingModel.java  embed/OpenAiCompatEmbeddingModel.java
   │             store/VectorStore.java  store/DbFlatVectorStore.java
   │             prompt/PromptBuilder.java
   ├─ llm/       KbChatModel.java  MockChatModel.java  OpenAiCompatChatModel.java
   ├─ mq/        MessageSender.java  InMemoryMessageSender.java  RocketMqMessageSender.java
   │             ImportTaskConsumer.java   # 唯一消费者，按 taskType 分派
   ├─ redis/     RedisClientFactory.java  RateLimiter.java  ConversationMemory.java
   ├─ plugin/    Tool.java(@Tool 注解)  ToolMeta.java  ToolRegistry.java  PluginExecutor.java
   │             impl/CalculatorTool.java  impl/DateTimeTool.java  impl/KnowledgeSearchTool.java
   ├─ entity/    Document.java  Chunk.java  Task.java  Conversation.java  Message.java
   ├─ mapper/    DocumentMapper.java  ChunkMapper.java  TaskMapper.java  ConversationMapper.java  MessageMapper.java
   └─ config/    KbProperties.java  MqConfig.java  RedisConfig.java  ChatProviderConfig.java  MybatisPlusConfig.java
```

原始文件不引入对象存储（MinIO 已砍），直接存本地 `data/files/<docId>.<ext>`（应用工作目录下）。

## 3. 模块职责与接口约定

### 3.1 controller（REST，统一返回 `{code, message, data}`，业务异常→ GlobalExceptionHandler）

| 接口 | 方法 | 行为 |
|---|---|---|
| /api/health | GET | 返回 200 `{"status":"UP"}`，供启动验证 |
| /api/documents | POST | multipart 上传 MD/TXT/DOCX → 落库(documents, PENDING task) + 发 MQ → 立即返回 `{taskId, docId}`；同 user 同 MD5 已存在则不重复导入，返回 `duplicate:true` + 原 docId |
| /api/documents/{docId} | DELETE | 逻辑删除 + 删除该 doc 全部 chunks（向量重建入口） |
| /api/documents/{docId}/chunks | GET | 返回 chunks（含 speaker/msg_time），供脱敏与切分验证 |
| /api/tasks/{taskId} | GET | 返回 status/progress/failReason |
| /api/chat | POST | `{question, conversationId?}` → 限流检查 → 会话记忆拼接 → 向量检索 top-k → Prompt → LLM（可触发插件）→ 落 messages → 返回 `{answer, citations:[{docId,chunkId,snippet,score}], pluginCalls}` |
| /api/conversations/{id}/messages | GET | 会话历史 |
| /api/chats/import | POST | JSON 聊天记录（数组：speaker/time/content/type）→ 建 task 走同一条 MQ 流水线 |
| /api/plugins | GET | 返回注册表中全部插件元信息 |

限流触发返回 HTTP 429 + `{code:429}`；上传文件大小上限 20MB（spring.servlet.multipart.max-file-size）。

### 3.2 rag

- **DocSplitter**：按段落聚合切分，目标 chunk-size 500 字符、overlap 50（配置 kb.rag.*）；文档类型不同阈值可配。
- **ChatSplitter**：**不按固定字数**——按"时间窗口 + 问答配对"聚合成 chunk，metadata 保留 speaker/msg_time（追问链 C23/C24 的答案落点）。
- **KbEmbeddingModel**：接口 `float[] embed(String text)`。
- **MockEmbeddingModel**（默认）：确定性伪向量——字符 bigram 分词 → hash 到 256 维桶 TF 加权 → L2 归一化；无网络无 key，余弦相似度语义近似可用。
- **OpenAiCompatEmbeddingModel**：langchain4j-open-ai 1.16.3，走 OpenAI 兼容 /v1/embeddings。
- **VectorStore / DbFlatVectorStore**：从 chunks 表加载向量到内存，余弦扁平扫描取 top-k，再按 score-threshold 截断（替代 Rerank）。量级 <10 万条，可接受；"换 Milvus 只换实现类"是面试话术。
- **PromptBuilder**：system prompt 强约束"只依据以下资料回答，无据不答，答案带 [n] 标注"；chunk 以 `[n] (doc:filename) content` 格式拼接；多轮会话拼最近 N 轮（N=kb.chat.history-turns）。

### 3.3 llm

- **KbChatModel**：接口 `ChatResult chat(List<ChatMessage> messages, List<ToolMeta> tools)`，ChatResult 含 `content + toolCalls`。
- **MockChatModel**（默认，无 key 全链路可跑）：规则式抽取回答——对 top chunk 做句子级关键词打分，抽取相关句拼接为答案并保留 [n] 引用；工具选择用简化规则（问题关键词命中 ToolMeta.description 关键词 → 发起 toolCall）。
- **OpenAiCompatChatModel**：langchain4j-open-ai 1.16.3 + Function Calling（tool schema 由 ToolRegistry 生成）。
- **切换说明（写进 README）**：`kb.llm.provider=openai` + 环境变量 `OPENAI_API_KEY` + 可选 `base-url`（任何 OpenAI 兼容网关）即切真模型；key 为空时 provider 强制回落 mock（启动时校验并 WARN）。换模型不改业务代码，只改配置——对应追问链 #49。

### 3.4 mq（降级开关，代码保持 MQ 语义）

- 统一抽象 `MessageSender.send(String topic, String jsonPayload)`，topic 固定 `KB_TASK`，payload 为 `{taskId, taskType, docId}`。
- `kb.mq.mode=memory`（默认）：**InMemoryMessageSender**——每个 topic 一个 LinkedBlockingQueue + 消费线程池；语义与真 MQ 对齐：异步投递、消费失败重试（最多 3 次、指数退避）、超限进"死信"（日志 + task 置 FAILED）。
- `kb.mq.mode=rocketmq`：**RocketMqMessageSender**——rocketmq-client 5.3.0 DefaultMQProducer；消费端 DefaultMQPushConsumer 手动 ACK。
- **幂等消费**（两种模式共用）：消费者先对 task 做状态机 CAS（仅 PENDING→RUNNING 的那条更新生效，MyBatis-Plus update where status），重复投递直接跳过——对应追问链 #90。
- 状态机：PENDING → RUNNING → SUCCESS / FAILED（FAILED 带 failReason 落库，progress = 已完成步骤/总步骤×100）。

### 3.5 redis

- **RateLimiter**：Lua 令牌桶（EVAL，Redis 3.0 兼容），key `rl:{userId}`，qps/capacity 可配；Jedis 连接失败时降级放行 + ERROR 日志（诚实兜底）。
- **ConversationMemory**：key `conv:{conversationId}`，存最近 N 轮问答 JSON，TTL 30 分钟。
- **RedisClientFactory**：jedis 5.0.2 直连（探针已验证），连接池参数可配。

### 3.6 plugin（P1 轻量版）

- `@Tool(name, description)` 注解标在 @Component 的 public 方法上；**ToolRegistry** 启动时扫描注册 `Map<String, ToolMeta>`（name/description/method/参数 JSON Schema 简化版）。
- **PluginExecutor**：最大调用轮次 3、单次执行超时 2s、失败把错误文本回传模型自愈；防死循环（追问链 #62/#63）。
- 内置 3 个：CalculatorTool（算术表达式求值）、DateTimeTool（当前时间/日期差）、KnowledgeSearchTool（关键词查 chunks 兜底）。
- 明确不做 SPI/ServiceLoader/自定义类加载器；"第三方插件"只写进后续规划（追问链 #60 话术）。

### 3.7 聊天记录导入（P1）

POST /api/chats/import 收 JSON 数组（speaker/time/content/type）→ 建 documents 行（doc_type=CHAT）+ task → MQ → ChatSplitter 切分（speaker/msg_time 进 chunks 元数据）→ 正则脱敏（手机号/身份证/邮箱替换为 `[REDACTED:PHONE]` 等，规则常量集中定义）→ 向量化入库。

## 4. 数据库 DDL（H2 默认 / MySQL 可选 profile，两份同构）

H2 文件模式：`jdbc:h2:file:./data/kb;MODE=MySQL`（数据跨重启保留）；MySQL profile 见 application-mysql.yml（因 root 密码未知，默认不启用、不验证连通）。schema-h2.sql 由 Spring `spring.sql.init` 启动执行。

```sql
CREATE TABLE IF NOT EXISTS documents (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  doc_id       VARCHAR(64)  NOT NULL,
  user_id      VARCHAR(64)  NOT NULL DEFAULT 'default',
  filename     VARCHAR(255) NOT NULL,
  doc_type     VARCHAR(16)  NOT NULL,               -- MD/TXT/DOCX/CHAT
  content_md5  CHAR(32)     NOT NULL,
  storage_path VARCHAR(512) NOT NULL,
  size_bytes   BIGINT       NOT NULL DEFAULT 0,
  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE', -- ACTIVE/DELETED
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_documents_docid UNIQUE (doc_id),
  CONSTRAINT uk_documents_user_md5 UNIQUE (user_id, content_md5)   -- MD5 幂等
);

CREATE TABLE IF NOT EXISTS chunks (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  chunk_id    VARCHAR(64) NOT NULL,
  doc_id      VARCHAR(64) NOT NULL,
  seq         INT         NOT NULL,
  content     TEXT        NOT NULL,
  speaker     VARCHAR(128),                          -- 聊天 chunk 说话人；文档为 NULL
  msg_time    TIMESTAMP,                             -- 聊天 chunk 消息时间
  embedding   TEXT        NOT NULL,                  -- float32 数组序列化（Base64）
  dim         INT         NOT NULL,
  token_count INT         NOT NULL DEFAULT 0,
  created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_chunks_chunkid UNIQUE (chunk_id)
);
CREATE INDEX IF NOT EXISTS idx_chunks_doc ON chunks (doc_id);

CREATE TABLE IF NOT EXISTS tasks (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  task_id     VARCHAR(64)  NOT NULL,
  task_type   VARCHAR(32)  NOT NULL,                 -- IMPORT_DOC / IMPORT_CHAT
  status      VARCHAR(16)  NOT NULL,                 -- PENDING/RUNNING/SUCCESS/FAILED
  doc_id      VARCHAR(64),
  payload     TEXT,                                  -- 任务参数 JSON
  progress    INT          NOT NULL DEFAULT 0,
  retry_count INT          NOT NULL DEFAULT 0,
  fail_reason VARCHAR(1024),
  created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_tasks_taskid UNIQUE (task_id)
);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks (status);

CREATE TABLE IF NOT EXISTS conversations (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  conversation_id VARCHAR(64) NOT NULL,
  user_id         VARCHAR(64) NOT NULL DEFAULT 'default',
  title           VARCHAR(255),
  created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_conv_id UNIQUE (conversation_id)
);

CREATE TABLE IF NOT EXISTS messages (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  message_id        VARCHAR(64) NOT NULL,
  conversation_id   VARCHAR(64) NOT NULL,
  role              VARCHAR(16) NOT NULL,            -- user/assistant
  content           TEXT        NOT NULL,
  citations         TEXT,                            -- JSON [{chunkId,docId,snippet,score}]
  plugin_calls      TEXT,                            -- JSON [{tool,args,result}]
  prompt_tokens     INT NOT NULL DEFAULT 0,
  completion_tokens INT NOT NULL DEFAULT 0,
  created_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_messages_msgid UNIQUE (message_id)
);
CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages (conversation_id);
```

MySQL 版差异：TEXT→可用 TEXT/MEDIUMTEXT、`CREATE INDEX IF NOT EXISTS` 改为先 DROP 再建或单独 CREATE INDEX（MySQL 8 不支持索引 IF NOT EXISTS）。

## 5. 配置键总表（application.yml 默认值）

```yaml
spring:
  datasource: { url: "jdbc:h2:file:./data/kb;MODE=MySQL", driver-class-name: org.h2.Driver, username: sa, password: "" }
  sql.init: { mode: always, schema-locations: classpath:schema-h2.sql }
  servlet.multipart: { max-file-size: 20MB, max-request-size: 25MB }
kb:
  llm:
    provider: mock                       # mock | openai
    openai: { base-url: "https://api.openai.com/v1", api-key: "${OPENAI_API_KEY:}", chat-model: gpt-4o-mini, embedding-model: text-embedding-3-small }
  mq:
    mode: memory                         # memory | rocketmq
    rocketmq: { namesrv-addr: "127.0.0.1:9876", topic: KB_TASK, consumer-group: kb_consumer }
  rag: { chunk-size: 500, chunk-overlap: 50, top-k: 5, score-threshold: 0.15 }
  redis: { host: 127.0.0.1, port: 6379, timeout-ms: 2000 }
  rate-limit: { qps: 5, capacity: 10 }
  chat: { history-turns: 5 }
```

## 6. 分工建议（impl-ds = 主力长任务 / impl-glm = P1 与文档）

**impl-ds（P0 全链，先动工）**：pom.xml 与骨架、common/config、entity/mapper、schema-h2.sql + schema-mysql.sql、DocumentController + DocService（上传/MD5 幂等/本地存储）、mq 全部（MessageSender / InMemory / RocketMq / ImportTaskConsumer + 状态机）、rag 的 DocSplitter + embed 两实现 + DbFlatVectorStore、llm 两实现、RagService + ChatService + PromptBuilder、RateLimiter、HealthController、run.cmd。
**impl-glm（P1 + 收尾，与 impl-ds 并行，接口契约以本文为准）**：plugin 全部（@Tool/ToolRegistry/PluginExecutor/3 个插件 + OpenAiCompatChatModel 的 Function Calling 对接）、ChatImportController + ChatImportService + ChatSplitter + 脱敏规则、ConversationMemory、data/demo-chat.json、jmeter/qa-load.jmx + jmeter/README.md、README.md、设计文档.md。
**契约纪律**：API 路径/响应结构、表结构、配置键即冻结契约；任何改动必须先报 captain 同步给另一方，不得私改。

## 7. 验收清单（每条机械可判）

| # | 验收项 | 判定方式 |
|---|---|---|
| 1 | 可打包 | `mvn.cmd -B -s <ws>\tools\settings.xml package -DskipTests` exit 0，且 target/ai-teaching-kb-1.0.0.jar 存在 |
| 2 | 单测通过 | 同上命令去掉 -DskipTests，exit 0 |
| 3 | 可启动 | run.cmd 存在；启动后 60s 内 `GET /api/health` 返回 200 且 body 含 "UP" |
| 4 | 导入链路 | `POST /api/documents` 上传 test.txt → 200 返回 taskId；`GET /api/tasks/{taskId}` 在 60s 内变 SUCCESS；`GET /api/documents/{docId}/chunks` 返回 ≥1 条 |
| 5 | MD5 幂等 | 同一文件第二次上传 → 响应含 `duplicate:true`，documents 表无新增行（chunks 数量不变） |
| 6 | 离线问答 | 无任何环境变量 key 的前提下 `POST /api/chat` → 200，answer 非空，citations ≥1 条且含 docId/chunkId |
| 7 | 限流 | 容量 10 配置下连发 15 次 `POST /api/chat` → 至少 1 次返回 429 |
| 8 | 插件 | `GET /api/plugins` 返回恰好 3 条；`POST /api/chat` 问 "23*17+5 等于多少" → answer 含 "396" 且 pluginCalls 非空 |
| 9 | 聊天导入+脱敏 | `POST /api/chats/import` 提交 data/demo-chat.json → task SUCCESS；返回的 chunks 中存在 speaker 非空样本，且内容不含示例手机号原文（被 [REDACTED: 替换） |
| 10 | 降级开关 | application.yml 默认 kb.llm.provider=mock、kb.mq.mode=memory；源码存在 OpenAiCompatChatModel.java 与 RocketMqMessageSender.java 两个分支实现（切 openai/rocketmq 时装配对应 bean） |
| 11 | DDL 完整 | schema-h2.sql 与 schema-mysql.sql 均含 documents/chunks/tasks/conversations/messages 五表建表语句与 uk_documents_user_md5 唯一约束 |
| 12 | 禁项检查 | grep pom.xml 无 postgresql/pgvector/spring-ai/docker 依赖；全仓库无硬编码真实 API key（api-key 仅 `${OPENAI_API_KEY:}` 占位） |
| 13 | 压测交付 | jmeter/qa-load.jmx 存在且 jmeter/README.md 写明场景/并发/样本量（结果数字留空由用户自测） |
| 14 | 文档 | README.md 含快速开始（构建命令、启动、≥3 个 curl 示例）与"未实现与后续规划"章节 |

## 8. 移交下游（开放问题，不在本任务解决）

1. 规格文档写"PDF/MD/TXT"，环境事实写"MD/TXT/DOCX"——**本任务书以环境事实为准（MD/TXT/DOCX，DOCX 用 POI，联网下载可接受）**；是否补 PDF（PDFBox 联网下载）由 implementation 自行决定，不做硬性要求。
2. RocketMQ 真机链路（mode=rocketmq）因本机 9876 未监听且安装目录不可写，**无法端到端验证**；验收只覆盖代码分支存在性（第 10 条），真机联调留给用户自测。
3. MySQL profile 同理（root 密码未知），只交付 application-mysql.yml + schema-mysql.sql，不验证连通。
4. JMeter 压测数字（P99 等）由用户自测，团队只交付脚本与说明。
5. MockChatModel 的抽取式回答质量只为演示链路，不承诺回答准确率指标（规格中的"准确率 __%"数字由用户后续自建测试集补）。
