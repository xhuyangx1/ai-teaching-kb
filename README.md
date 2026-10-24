# AI 教学知识库平台（ai-teaching-kb）

> 一个**离线可跑**的教学资料 RAG 问答服务：把 Markdown / TXT / DOCX 教学文档与聊天记录导入知识库，
> 切片、向量化、建索引，然后基于检索到的片段回答问题并**给出引用溯源**。
> 全程不需要 API key、不需要 Docker、不需要外部中间件即可完整演示。

技术栈：Java 17 · Spring Boot 3.5.5 · MyBatis-Plus 3.5.9 · H2（默认）/ MySQL 8（可选）
· LangChain4j（OpenAI 兼容）· Jedis + Lua 限流 · RocketMQ（可选分支）· Apache POI。

---

## 1. 快速开始

### 1.1 构建

```bat
tools\apache-maven-3.9.9\bin\mvn.cmd -B -s tools\settings.xml package -DskipTests
```

产物：`target/ai-teaching-kb-1.0.0.jar`（Spring Boot fat jar，约 122 MB，内含所有依赖）。
`-DskipTests` 可去掉以同时跑单元测试。

### 1.2 启动

```bat
run.cmd
```

默认监听 **8080**，使用 H2 文件库（`./data/kb.mv.db`，跨重启保留）、内存消息队列、mock 模型。
透传任意 Spring Boot 参数，例如换端口：

```bat
run.cmd --server.port=9090
```

> 首次启动约 10–15 秒。看到 `Started KbApplication` 即就绪。
> 自检：`curl http://127.0.0.1:8080/api/health`

### 1.3 两条 curl 示例

**示例一：导入文档并查询任务状态**（导入是异步的，接口立即返回 PENDING 任务号）

```bash
curl -X POST "http://127.0.0.1:8080/api/documents?userId=demo" \
     -F "file=@README.md"
# -> {"code":200,"data":{"docId":"d_xxx","taskId":"t_xxx","taskStatus":"PENDING",...}}

curl "http://127.0.0.1:8080/api/tasks/t_xxx"
# -> {"code":200,"data":{"status":"SUCCESS","progress":100,...}}
```

**示例二：基于知识库提问（返回答案 + 引用溯源）**

```bash
curl -X POST "http://127.0.0.1:8080/api/chat" \
     -H "Content-Type: application/json" \
     -d '{"question":"文档切分为什么按段落聚合？","userId":"demo"}'
# -> {"code":200,"data":{"answer":"根据知识库资料，答案如下：\n- ... [1]",
#      "citations":[{"refIndex":1,"docId":"d_xxx","chunkId":"c_xxx",
#                    "filename":"README.md","snippet":"...","score":0.4834}],
#      "pluginCalls":[],"model":"mock-extractive"}}
```

**示例三：插件（计算器）——answer 里直接能看到工具结果**

```bash
curl -X POST "http://127.0.0.1:8080/api/chat" \
     -H "Content-Type: application/json" \
     -d '{"question":"23*17+5 等于多少？","userId":"demo"}'
# -> "answer":"计算结果：23*17+5 = 396"
#    "pluginCalls":[{"tool":"calculator","result":"23*17+5 = 396"}]
```

**示例四：聊天记录导入（自动脱敏）**

```bash
curl -X POST "http://127.0.0.1:8080/api/chats/import" \
     -H "Content-Type: application/json" \
     --data-binary @data/demo-chat.json
```

### 1.4 一键验证

```bat
verify-all.cmd
```

自动完成：构建检查 → 启动服务 → 健康检查 → 导入三种格式 → 轮询任务 → 问答取引用 →
插件调用 → MD5 幂等 → 查询任务历史 → 关闭服务，并逐条打印 PASS/FAIL。

---

## 接入真实大模型（OpenAI 兼容网关）

默认 `kb.llm.provider=mock`（离线、无 key、规则式抽取），**任何 OpenAI 兼容网关**都可通过配置切换：

```bash
# 方式一：环境变量 + 启动脚本
set OPENAI_BASE_URL=https://api.siliconflow.cn/v1
set OPENAI_API_KEY=sk-xxxx
set OPENAI_CHAT_MODEL=Qwen/Qwen2.5-7B-Instruct
start-real-llm.cmd

# 方式二：直接给 jar 传参
java -jar target/ai-teaching-kb-1.0.0.jar ^
  --kb.llm.provider=openai ^
  --kb.llm.openai.base-url=http://127.0.0.1:8326/v1 ^
  --kb.llm.openai.api-key=sk-xxxx ^
  --kb.llm.openai.chat-model=buddy/deepseek-v4.1-flash ^
  --kb.llm.embedding-provider=mock
```

### chat 与 embedding 为什么必须能独立配置

实测不少企业级/自建 OpenAI 兼容网关**只代理 `/chat/completions`，`/embeddings` 返回 404**。
若两者绑死，用户想用真实对话模型就必须放弃向量检索。本项目把它们拆成两个配置键：

| 配置 | 作用 |
|---|---|
| `kb.llm.provider` | 对话模型：`mock` / `openai` |
| `kb.llm.embedding-provider` | 向量模型：留空=跟随 chat；显式写 `mock` 可实现"真实模型回答 + 本地向量检索"的**混合模式** |

混合模式下启动日志会打印明确提示（向量召回精度低于真实 embedding，但 RAG 全链路可用）。

### 接真实网关时修掉的两个真实缺陷

**① 工具调用消息顺序错误（会导致网关直接报错）**

原实现把 `tool` 结果消息加在 `assistant(tool_calls)` **之前**，违反 OpenAI 协议的顺序要求。
严格网关会直接拒绝并返回：
`tool calls and tool results do not match, please start a new conversation`。
mock 模型不校验消息顺序，所以这个缺陷只在接真实网关时才暴露。已修正为
`assistant(tool_calls) → tool results`。

**② 工具结果被"只依据资料"的规则压制**

工具（如 calculator）算出结果回灌后，模型仍套用 system prompt 的"只依据【资料】回答"，
产出"资料中未涉及数学计算，无法回答"这类自相矛盾的回答。修法：system prompt 增加工具使用规则
（工具结果视为可信事实、无需 `[n]` 引用），并在工具回灌轮次追加一条显式指令。

### 真实模型实测结果

```bash
# 前置：应用以 provider=openai 启动在 18099
powershell -ExecutionPolicy Bypass -File tools\real-llm-smoke.ps1
```

| 场景 | 结果 |
|---|---|
| 资料命中问答 | 正确区分缓存穿透/击穿的"数据是否存在"与"热点过期"，带 `[1]` 引用标注 |
| Function Calling | 模型自主决策调用 `calculator`（args `{"expression":"23*17+5"}`），回答含 396，**不拒答** |
| Function Calling | 自主调用 `datetime`，回答含真实时间 |
| 长文检索 | 命中多段文档内容 |

## 分片上传（断点续传）

大文件 / 弱网场景走三段式协议，客户端可中断后**只补缺失分片**：

```bash
# 1) 初始化（给 fileMd5 可秒传：内容已在库中则一个分片都不用传）
curl -X POST http://127.0.0.1:8080/api/uploads/init -H "Content-Type: application/json" \
  -d '{"filename":"big.md","totalSize":10485760,"chunkSize":5242880,"fileMd5":"<整文件MD5>","userId":"u1"}'
# → {"uploadId":"u_xxx","chunkSize":5242880,"totalChunks":2,"instant":false}

# 2) 逐片上传（seq 从 0 开始；同 seq 重传 = 覆盖，重试无害）
curl -X PUT "http://127.0.0.1:8080/api/uploads/u_xxx/chunks/0?userId=u1&chunkMd5=<分片MD5>" \
  -F "chunk=@part0.bin"

# 3) 任意时刻查进度 —— 断点续传的关键：只补 missingSeqs
curl "http://127.0.0.1:8080/api/uploads/u_xxx/progress?userId=u1"
# → {"receivedSeqs":[0],"missingSeqs":[1],"percent":50}

# 4) 合并（校验齐备 + 整文件 MD5 强校验 → 落库 → 投递导入任务，幂等）
curl -X POST "http://127.0.0.1:8080/api/uploads/u_xxx/merge?userId=u1"
# → {"docId":"d_xxx","taskId":"t_xxx","taskStatus":"PENDING"}

# 5) 放弃上传（清理分片）
curl -X DELETE "http://127.0.0.1:8080/api/uploads/u_xxx?userId=u1"
```

**设计要点**：

| 关注点 | 做法 |
|---|---|
| 断点续传的权威来源 | `upload_chunks` 表 + 磁盘分片文件**双确认**，不依赖客户端上报、也不依赖 Redis——服务重启后进度依然准确 |
| 分片幂等 | 唯一约束 `uk_upload_chunk(upload_id, seq)`，同 seq 重传即覆盖 |
| 完整性 | 分片级 MD5（挡住网络截断的坏片）+ 合并后整文件 MD5 强校验（不匹配则清理临时产物、保留分片便于定位坏片） |
| 合并原子性 | 先写 `_merged.tmp` 再原子改名，保证最终文件"要么完整、要么不存在" |
| 并发保护 | 会话状态 CAS（UPLOADING→MERGED），并发 merge 只成功一次，不会产生两份文档 |
| 秒传 | init 时按 fileMd5 预判，命中直接返回已有 docId |
| 存储回收 | 会话 TTL（默认 24h）+ 定时任务回收孤儿分片，客户端传一半跑掉也不会永久占盘 |
| 与整体上传的关系 | 合并后调用同一个 `registerMergedFile` 接入既有流水线，**切分/向量化/RAG 链路零改动** |

## 2. 架构说明

### 2.1 分层

```
controller/   REST 接口（统一返回 {code,message,data}）
service/      业务编排：DocService / RagService / ChatService / TaskService
  └ impl/     实现（导入、检索、问答、任务状态机）
rag/          检索增强生成的核心
  ├ splitter/ DocSplitter（段落聚合+重叠）、ChatSplitter（时间窗口切分）
  ├ embed/    KbEmbeddingModel 接口 + Mock（离线）/ OpenAI 兼容 两种实现
  ├ store/    VectorStore 接口 + DbFlatVectorStore（内存扁平余弦检索）
  └ prompt/   PromptBuilder（只依据资料 + [n] 引用约束）
llm/          KbChatModel 接口 + Mock（离线抽取式）/ OpenAI 兼容 两种实现
mq/           MessageSender 接口 + 内存队列 / RocketMQ 两种实现；消费者与任务分派
redis/        Lua 令牌桶限流、会话记忆、客户端 IP 解析
plugin/       @Tool 注解 + 注册表 + 执行器（轮次上限/超时），3 个内置插件
chatlog/      聊天记录导入与敏感信息脱敏
entity/       五张表实体        mapper/  MyBatis-Plus Mapper
common/       统一响应、异常处理、traceId、JSON 工具
config/       配置绑定与 Bean 装配
```

### 2.2 五张表

| 表 | 用途 |
|---|---|
| `documents` | 文档/聊天记录元数据，`uk_documents_user_md5` 保证同用户同内容幂等 |
| `chunks` | 切片 + 向量（Base64 float32）+ `speaker`/`msg_time` 元数据 |
| `tasks` | 异步任务状态机：PENDING → RUNNING → SUCCESS / FAILED（含失败原因） |
| `conversations` / `messages` | 会话与消息（含 citations / pluginCalls 的 JSON 快照） |

### 2.3 问答链路（关键时序）

```
POST /api/chat
  → Redis Lua 令牌桶限流（用户维度 + IP 维度，任一超限 = 429）
  → 取会话记忆（最近 N 轮；Redis 不可用则回落 DB 历史）
  → 向量检索 top-k（扁平余弦）→ score-threshold 截断
  → PromptBuilder 组装 system 约束 + [n] 资料块
  → KbChatModel 生成（可触发插件，最多 3 轮）
  → 落 messages（含 citations/pluginCalls）
  → 返回 {answer, citations[], pluginCalls[]}
```

### 2.4 文档导入链路（异步）

```
POST /api/documents
  → 计算 MD5 → 命中则直接返回已有文档（duplicate=true）
  → 落盘 data/files/<docId>.<ext> + 写 documents 行 + 建 PENDING 任务
  → 事务提交后投递消息（afterCommit，避免消费者读到未提交的行）
  → [消费线程] CAS 抢占任务 → 解析(MD/TXT/DOCX) → 切片 → 向量化 → 写 chunks
  → 任务置 SUCCESS / FAILED（失败带原因，可查 /api/tasks/{taskId}）
```

### 2.5 配置开关一览

| 配置 | 默认 | 说明 |
|---|---|---|
| `kb.llm.provider` | `mock` | `mock` 离线抽取式；`openai` 走 OpenAI 兼容接口 |
| `app.mq.enabled` | `false` | `false` 内存队列（语义与真 MQ 对齐）；`true` 走 RocketMQ |
| `kb.rate-limit.trust-forwarded-for` | `false` | 是否信任 X-Forwarded-For（见 §5 安全说明） |
| `kb.rag.chunk-size` / `overlap` | `500` / `50` | 切片长度与重叠 |
| `kb.rag.top-k` / `score-threshold` | `5` / `0.15` | 检索条数与相关性截断 |
| `kb.mq.retry.max-attempts` | `3` | 消费失败重试次数（含首次），超限进死信 |

### 2.6 切换真实大模型

```bash
# 任何 OpenAI 兼容网关都可以
export OPENAI_API_KEY=sk-...
java -jar target/ai-teaching-kb-1.0.0.jar \
  --kb.llm.provider=openai \
  --kb.llm.openai.base-url=https://api.openai.com/v1 \
  --kb.llm.openai.chat-model=gpt-4o-mini
```

未配置 key 时即使显式指定 `openai`，启动也会**强制回落 mock 并打 WARN**——
保证任何环境都能启动、无 key 也能跑通完整链路。

> ⚠️ **切换 provider 后请重建索引**：mock 向量是 256 维、OpenAI 是 1536 维，维度不同无法比较余弦。
> 做法是删除已有文档后重新导入（`DELETE /api/documents/{docId}` 会清掉其切片）。

---

## 3. 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查，并暴露当前生效的 llm/embedding/mq/限流实现 |
| POST | `/api/documents` | 上传文档（MD/TXT/DOCX），异步导入 |
| GET | `/api/documents` | 列出有效文档（调试用） |
| DELETE | `/api/documents/{docId}` | 逻辑删除并清理切片 |
| GET | `/api/documents/{docId}/chunks` | 查看切片（含 speaker/msgTime，不含向量） |
| GET | `/api/tasks/{taskId}` | 查询任务状态/进度/失败原因 |
| POST | `/api/chat` | 问答，返回答案 + 引用 + 插件调用 |
| GET | `/api/conversations/{id}/messages` | 会话历史 |
| POST | `/api/chats/import` | 导入聊天记录 JSON（自动脱敏） |
| GET | `/api/plugins` | 已注册插件列表 |
| GET | `/api/ops/tasks` | 任务历史（支持 `?status=FAILED`、`?limit=`） |
| GET | `/api/ops/tasks/failures` | 仅失败任务（含原因） |
| GET | `/api/ops/tasks/stats` | 各状态任务计数 |
| GET | `/api/ops/mq` | 队列深度、死信、幂等跳过计数 |
| GET | `/api/ops/rate-limit` | 限流实况与降级状态 |

---

## 4. 目录结构

```
ai-teaching-kb/
├─ pom.xml                    构建定义
├─ run.cmd                    启动脚本（纯 ASCII，见「踩坑记录」）
├─ verify-all.cmd             一键验证脚本
├─ README.md                  本文档
├─ 设计文档.md                 设计说明（七节）
├─ TASKS.md                   施工任务书与验收标准
├─ VERIFICATION.md            真机验证报告
├─ REVIEW.md                  代码审查报告
├─ jmeter/                    压测脚本与说明（数字由用户自测）
├─ data/demo-chat.json        示例聊天记录（含脱敏验证样本）
├─ data/files/                上传原文落盘
└─ src/main/
   ├─ java/com/aiteaching/kb/ 源码（见 §2.1）
   └─ resources/
      ├─ application.yml          默认：H2 + mock + 内存队列
      ├─ application-mysql.yml    可选 profile：MySQL 8
      ├─ schema-h2.sql / schema-mysql.sql   五表 DDL（同构）
      └─ META-INF/spring.factories         注册环境后处理器
```

---

## 5. 安全与运维须知

### 5.1 X-Forwarded-For 默认不信任

`kb.rate-limit.trust-forwarded-for` **默认 false**：该请求头由客户端提供，若默认信任，
攻击者每次换一个 XFF 值就能拿到新的令牌桶，IP 维度限流形同虚设。
默认只认 TCP 层的 `remoteAddr`。

**部署在可信反向代理之后时**（Nginx / SLB 且会重写该头）才应开启：

```bash
run.cmd --kb.rate-limit.trust-forwarded-for=true
```

```nginx
# 代理侧需覆写该头，避免客户端伪造值被透传
proxy_set_header X-Forwarded-For $remote_addr;
```

### 5.2 Redis 降级语义（重要）

Redis 承担**限流**与**会话记忆**，两者都是增强而非业务必需：

- **不可用时放行**：限流直接通过并打 WARN，不在 Redis 故障时把服务变成不可用；
- **单向闩锁**：一旦标记为不可用，进程内**不会自动重探**，此后限流持续放行，直到重启才恢复。
  这是为了不在热点路径上反复重试超时连接；生产环境如需自动恢复，应加低频重探（见「未实现与规划」）。

### 5.3 限流与操作端点

- 用户维度：`kb.rate-limit.qps` / `capacity`（默认 5 / 10）
- IP 维度：`kb.rate-limit.ip-qps` / `ip-capacity`（默认 50 / 100）
- 超限返回 **HTTP 429** + `{"code":429,...}`
- `/api/ops/**` 是只读运维端点，**建议不要直接暴露到公网**（未做鉴权）。

### 5.4 H2 文件库与受限运行环境

在部分受限令牌环境下（Windows 低完整性级别 + Mandatory Label NW），
`java.nio.file.Files.isWritable()` 会对**所有**文件返回 false，而真实写入其实成功。
H2 的 `Engine.openSession` 依据该返回值把连接强制设为只读，于是：

- **首次启动**：正常读写；
- **重启后**：对已有库的写入报 `SQLState 90097 "The database is read only"`（读操作不受影响）。

这不是应用缺陷。启动时 `H2StorageDiagnostic` 会检测并打印该情形与两种处置方式。
在普通终端运行即可正常持久化；若要在受限环境演示完整写入链路，删除 `data/kb.mv.db` 后重启。

---

## 6. 未实现与后续规划

> 诚实清单：以下功能**没有实现**。写在这里是为了不夸大交付范围——评审与面试时请以此为准。

### 6.1 明确砍掉（不在本期范围）

| 项 | 原因与替代方案 |
|---|---|
| **教学视频生成** | 原始规格中的视频生成能力整体砍掉，本项目只做文档知识库 + 问答。 |
| **PgVector / Milvus 等向量数据库** | 引入外部组件会破坏"离线可跑"。现用内存扁平余弦检索（`DbFlatVectorStore`），数据量 &lt;10 万切片时 P99 在毫秒级。**换向量库只需替换 `VectorStore` 实现，业务代码不动。** |
| **BM25 + RRF 混合检索** | 已砍。现为纯向量检索 + `score-threshold` 截断 + 关键词兜底（`KnowledgeSearchTool`）。 |
| **Rerank 精排模型** | 已砍，用「距离阈值 + top-k 截断」替代（低分回退时打 WARN）。 |
| **SSE 流式输出** | 已砍，问答为同步返回。 |
| **Docker / Docker Compose** | 不允许引入，交付物是可执行的 fat jar。 |
| **插件 SPI / 热插拔 / 自定义类加载器** | 只做 `@Tool` 注解 + 启动扫描注册，不做类加载隔离与运行时安装。 |
| **多租户 / 权限体系 / 后台管理界面** | 只保留 `userId` 字段做数据隔离维度，无认证鉴权。 |
| **MinIO 等对象存储** | 原文直接落本地 `data/files/`。 |

### 6.2 已知缺口（真实存在，非"没做"而是"没做全"）

| 缺口 | 现状 | 规划 |
|---|---|---|
| **崩溃/重启后 PENDING 任务无回收** | 消费依赖进程内内存队列；进程被杀或 broker 不可达时，任务会停在 PENDING/RUNNING，**全仓库没有定时补偿扫描**。 | 增加 `@Scheduled` 扫描：超过 N 分钟仍为 PENDING/RUNNING 且 `retry_count` 未超限的任务重新投递；配套加运维重投端点与 `tasks(created_at)` 索引。**在补齐之前，不能声称"服务挂了任务会自动恢复"已实现。** |
| **query 改写 / 指代消解** | 未实现。多轮追问中的"它""那个"不会被改写补全。 | 在检索前加一轮轻量改写（可用小模型或规则）。 |
| **评测集与准确率指标** | 未实现。仓库内**没有**准确率/召回率的实测数字，也未构建标注测试集。 | 构建教学问答测试集，接入检索命中率 / 答案忠实度评测。 |
| **Prompt 注入防护** | 仅靠 system prompt 约束"只依据资料回答"，没有输入侧过滤或输出侧校验。 | 增加资料与指令的边界标记、可疑指令检测。 |
| **插件沙箱与 schema 校验** | 插件直接反射调用，参数只按约定解析，无 JSON Schema 强校验、无权限隔离。 | 引入参数 schema 校验与调用白名单。 |
| **单测覆盖** | **仓库当前没有单元测试**（`mvn package` 不带 `-DskipTests` 也能过，因为没有测试类）。 | 补 DocSplitter / MockEmbeddingModel / 限流 / 状态机 CAS 的单测。 |
| **Redis 恢复后自动重探** | 见 §5.2，标记不可用后不再重探，需重启恢复。 | 低频重探（如 30s ping 成功即恢复）。 |
| **插件执行器线程池无上限** | 使用 `newCachedThreadPool`，被阻塞的第三方工具线程会堆积。 | 换有界线程池 + 拒绝策略，加请求级总预算。 |
| **上传回滚留下孤儿文件** | 先落盘再提交，事务回滚时 `data/files/` 会残留文件，无清理任务。 | 归档清理任务，或改为提交后落盘。 |
| **`GET /api/documents` 暴露存储路径** | 直接返回实体（含 `storagePath` 绝对路径），仅供调试。 | 改为 DTO 视图，去掉本地路径字段。 |

### 6.3 与原始规格文档的差异（面试务必对齐）

规格文档与追问链中提到的 **PgVector、MinIO、BM25→RRF→Rerank、SSE、Docker Compose**
在本实现中**都不存在**（原因见 §6.1）。如果按规格文档原样作答，会因为"代码里找不到 PgVector"而被追问穿。
请以 **`设计文档.md` §3 的选型对比**为准。

### 6.4 未经真机验证的部分

- **RocketMQ 真机收发**：本机 9876 未监听且安装目录不可写，只验证了"开关能选中该实现"与
  "不可用时任务诚实失败并记录原因"，**真实收发需在有 broker 的环境自测**。
- **MySQL profile 连通性**：只交付 `application-mysql.yml` 与同构 DDL，未验证连通（本机 root 密码未知）。
- **压测数字**：`jmeter/qa-load.jmx` 与说明已交付，**P99/吞吐等数字留空由用户自测**。
- **跨重启持久化**：受运行环境受限令牌影响（§5.4），未做跨重启写入验证。
- **openai 真实调用**：无 API key，仅验证了装配切换与无 key 回落。

---

## 7. 踩坑记录（真实遇到并已解决）

1. **`run.cmd` 必须是纯 ASCII**：`cmd.exe` 按 OEM 代码页（本机 GBK）解析 `.cmd`，
   UTF-8 中文注释会被当成命令执行导致脚本整体失败。中文输出交给 JVM 在运行时打印。
2. **事务内投递消息是个时序陷阱**：原实现在 `@Transactional` 里直接发 MQ 消息，
   消费者可能在事务提交前回查任务行 → 查不到 → 把消息当脏数据丢弃 → 任务永久 PENDING。
   改为事务提交后投递（`afterCommit`），回滚则不投递。
3. **逻辑删除仍占用唯一约束**：`uk_documents_user_md5` 是 `(user_id, content_md5)` 唯一，
   被逻辑删除的行**仍然占着这个键**。只按 `status='ACTIVE'` 回查会导致
   "上传 → 删除 → 再上传同一内容"直接 500。修法是回查不带 status 过滤，命中已删除行则**复活**原文档并重建索引。

---

## 8. 相关文档

- [设计文档.md](设计文档.md) —— 背景 / 架构 / 选型对比 / 时序 / 压测方法 / 异常兜底 / 未实现规划
- [TASKS.md](TASKS.md) —— 施工任务书与机械化验收清单
- [VERIFICATION.md](VERIFICATION.md) —— 全链路真机验证报告
- [REVIEW.md](REVIEW.md) —— 代码审查报告（含逐文件结论与问题清单）
- [jmeter/README.md](jmeter/README.md) —— 压测场景说明
