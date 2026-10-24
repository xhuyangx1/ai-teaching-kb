
# REVIEW.md — 核心逻辑审查报告（t8 / coredev）

> 审查人：coredev ｜ 日期：2026-10-03 ｜ 对象：t4（骨架+RAG+数据层）/ t5（MQ 异步+限流幂等）/ t6（插件+聊天导入+压测脚本）
> 方式：**只读静态审查**（逐文件读完 88 个 Java 文件 + 5 个资源 + pom/run.cmd/jmeter/DDL），执行证据一律引用 t7 的 VERIFICATION.md，未重复运行服务。
> 结论：**verdict = pass**（0 个 blocker、0 个 high；最高 severity = medium，共 6 条 medium + 17 条 low/info，逐条给出 requiredFix 并移交 t9）。
> **severity 口径（先定尺子再打分，避免见 bug 就给 high）**：blocker＝阻断交付/数据损坏/默认配置下可被利用的安全洞 ｜ high＝**主线用户路径**（14 条验收 + 演示脚本 + 追问链主流程）上的严重功能缺陷 ｜ medium＝**非主线路径**上的真实缺陷、需文档化的降级/配置风险、文档与实现不一致 ｜ low＝健壮性、清理、文档细节。
> 据此 **F1 定为 medium 而非 high**，理由写在 §3 表 F1 行（同时它仍是 t9 的第 1 优先修复项）。透明说明：若 captain 采用"发布阻断"口径，可将 F1 升级为 high 并在 integration 前修复——本报告的 requiredFix 与该口径不冲突。

---

## 0. 结论速览

| 维度 | 结论 |
|---|---|
| 功能正确性（对照 TASKS.md §7 十四条验收） | 14/14 有代码落点；t7 已实测 6/6 |
| 并发安全 | 核心路径（MD5 幂等、CAS 抢锁、向量缓存、限流 Lua）结论**成立**；发现 2 处次级竞态（F20/F16） |
| 降级路径 | LLM/MQ/Redis/向量阈值/插件异常**六条降级全部有代码且有日志**；缺"进程崩溃后 PENDING 回收"（F3） |
| 资源泄漏 | 未发现连接/线程泄漏；[PluginExecutorImpl] 线程池无上限（F11，low） |
| 面试视角（追问链） | **D/E 组可撑住**；**G 组需用户自测数字**；H91 / I100 / D44-D48 属"未实现"，必须在 README/设计文档明说（F3/F9） |
| 选型与契约一致性 | 包结构 3 处**有意偏离** TASKS.md §2 文件清单（理由成立，需文档回写）；规格文档里的 PgVector/BM25/Rerank 与实现不符（F9，面试踩雷点） |
| 【待裁决点】mock answer 不含工具结果 | **裁决：记为可接受的行为边界**（理由见 §1），但**必须**在 t9 落地"文档注明 或 3 行体验修复"其中之一 |

**最高优先级（t9 必做，否则不算完整交付）**：

1. **F1（medium，最高优先级）** 删除文档后重新上传**同一内容** → HTTP 500（真实缺陷，代码级可证；非主线路径故定 medium，但 t9 必须修 + 补回归用例）。
2. **F4（medium）** 交付物缺 [README.md] 与 [设计文档.md]（TASKS.md §2/§7-14 明确要求，属 t9 范围）。
3. **F3（medium）** 崩溃/重启后的 PENDING 任务无回收路径 → 文档必须写明"未实现 + 规划中的定时扫描"。
4. **F2（medium）** mock 下工具结果不进 answer → 二选一落地（修复或文档注明）。

---

## 1. 【待裁决点】MockChatModel 工具结果回灌后 answer 不含计算值 —— 裁决

**现象（t6 移交 + 代码复核确认）**：mock provider 下问"23*17+5 等于多少"，[pluginCalls[0].result = "23*17+5 = 396"] 正确，但最终 [answer] 走**抽取式**路径（[MockChatModel.chat()] 第二轮 [parseRefs] → 资料块里没有 396 → [refs] 为空或未命中关键词 → answer = "根据现有资料无法回答该问题：知识库中没有可用的资料片段。"）。即：**用户看到的答案说"无法回答"，而工具明明算出了 396**。openai 模式下模型会把工具结果编入 answer。

**裁决：记为「可接受的行为边界」，不因它判 needs_revision。** 理由三条：

1. **契约未要求**：TASKS.md §3.3 定义 MockChatModel 为"规则式抽取回答"，§8.5 明确"MockChatModel 的抽取式回答质量只为演示链路，不承诺回答准确率指标"。工具结果以结构化字段返回本身就满足 §3.4/§3.6 的插件语义。
2. **不是能力缺失**：同一条链路在 [kb.llm.provider=openai] 时 answer 会包含计算结果（[OpenAiCompatChatModel] 组装了 ToolExecutionResultMessage + AiMessage(requests)），差异属 **mock 实现的呈现边界**，不是架构缺陷。
3. **不影响任何机械验收**：t7 已按 pluginCalls 判定通过（VERIFICATION §5.2）；引用溯源、限流、幂等均不受影响。

**但必须落地一项（t9）**，因为验收 #8 的字面文案是"answer 含 396"，且演示时"答案说无法回答、结果藏在结构化字段里"观感偏差：

- **首选（低成本、体验好、约 3 行）**：[MockChatModel.chat()] 在收到工具结果回灌的轮次，把最近一次 ToolExecutionResultMessage 的文本拼进 answer（例如前置 "计算结果：" + toolOutput）；
- **次选（零风险）**：在 README「未实现与后续规划」+ 设计文档「异常与兜底」写明该边界，并说明"演示时看 pluginCalls 字段"。

> 口径提示：若采用"验收 #8 必须字面命中 answer"的严格口径，则本项应由 t9 做首选修复后复测一次；本报告从契约角度不因此项失败。

---

## 2. 逐文件审查结论（t4/t5/t6 全部交付物）

图例：✅ 通过 ｜ ⚠️ 通过但有改进项（编号见 §3） ｜ ❌ 缺陷

### 2.1 源码 src/main/java/com/aiteaching/kb/（88 个文件）

**根（1）**

| 文件 | 结论 |
|---|---|
| KbApplication.java | ✅ 标准启动类，无多余装配 |

**common（5）**

| 文件 | 结论 |
|---|---|
| ApiResponse.java | ✅ {code,message,data} 与契约一致 |
| BusinessException.java | ✅ code 驱动 HttpStatus 映射 |
| GlobalExceptionHandler.java | ✅ 429/413/404/500 映射正确；⚠️ 未处理 HttpMessageNotReadableException（F12，low） |
| TraceIdFilter.java | ✅ require/propagate/清除闭环正确 |
| JsonUtil.java | ✅ 统一 ObjectMapper + 失败转 BusinessException |

**config（6）**

| 文件 | 结论 |
|---|---|
| KbProperties.java | ✅ §5 配置键总表全覆盖（rate-limit 为契约的超集） |
| ChatProviderConfig.java | ✅ **无 key 强制回落 mock** 是"离线可跑"的关键兜底；⚠️ provider=openai 时 embedding 与历史切片维度可能不一致（纯配置态，F13 low） |
| MqConfig.java | ✅ 单一装配点 + @PreDestroy 关闭 |
| RedisConfig.java | ✅ 启动 ping 失败不阻断（诚实降级） |
| MybatisPlusConfig.java | ✅ 最小 MapperScan |
| H2StorageDiagnostic.java | ✅ **亮点**：把"受限令牌下 isWritable 误判 → H2 90097"讲清楚且不偷偷改行为（沙箱结论 ≠ 用户环境预期的诚实声明） |

**controller（4）**

| 文件 | 结论 |
|---|---|
| HealthController.java | ✅ 暴露 llm/embedding/mq/rateLimiter 实况，是"配置切换真的生效"的直接证据 |
| DocumentController.java | ✅ 上传/删除/切片/列表；⚠️ 列表回传实体含 storagePath（绝对路径外泄，F14 low） |
| ChatController.java | ✅ 走 ask(request) 默认方法 → 限流内部自取 IP（语义见 F18） |
| TaskController.java | ✅ 404 语义正确 |

**entity（5）/ mapper（5）**

| 文件 | 结论 |
|---|---|
| Document/Chunk/Task/Conversation/Message.java | ✅ 与 schema 五表列一一对应，IdType.AUTO 与 DDL AUTO_INCREMENT 一致 |
| 五个 Mapper | ✅ 仅继承 BaseMapper，无自定义 SQL ⇒ 无条件拼接注入面 |

**llm（8）**

| 文件 | 结论 |
|---|---|
| KbChatModel.java | ✅ 抽象稳定（messages+toolSpecs → content+toolCalls），换模型零改动 |
| MockChatModel.java | ✅ **可以讲透的实现**：Ref 解析走行首结构（非正则，避免内容含括号误判）；⚠️ F2（工具结果不回填 answer） |
| OpenAiCompatChatModel.java | ✅ schema 由 ToolSpec 转 ToolSpecification，依赖方向 plugin→llm 单向；⚠️ F11（60s 超时 + maxRetries=1，无熔断/无总预算） |
| ChatResult/ToolCall/ToolSpec/ToolCatalog/ToolExecutor（5 个） | ✅ 接缝清晰；ToolExecutor 的"可选注入"让 t4 可独立于插件模块运行 |

**mq（11）**

| 文件 | 结论 |
|---|---|
| MessageSender.java | ✅ topic 固定 + mode + shutdown |
| InMemoryMessageSender.java | ✅ 有界队列/拒绝入队/异常隔离/优雅关闭；⚠️ F15（computeIfAbsent 内启动消费线程）、F8（getDeadLetters 无调用方） |
| ImportTaskConsumer.java | ✅ **CAS 幂等 + 重试 + 死信**实现正确，attempt 语义（maxAttempts 含首次）与文档一致；脏消息→死信；无处理器→FAILED 留证 |
| TaskPublisher.java | ✅ **本任务最重要的修复**：afterCommit 投递 + 回滚不投递 + 投递失败落 FAILED。直击"事务未提交 → 消费者回查不到 → 任务永久 PENDING"的真实时序缺陷 |
| MqEnabledBridge.java | ✅ 单装配点归一化；⚠️ F10（addFirst 优先级高于命令行 kb.mq.mode） |
| MqProperties.java | ✅ 重试/退避/容量/worker 全可配；F8（deadLetterRetained 声明后未接线） |
| MqWiringConfigurer.java | ✅ 明确解释"为何用 SmartInitializingSingleton 而不改 config 包"，时机在收请求之前，安全 |
| RocketMqMessageSender.java | ✅ 手动 ACK / RECONSUME_LATER 语义正确；不可用时抛异常 → TaskPublisher 置 FAILED（诚实失败）；真机链路未验证（TASKS.md §8.2） |
| DocImportTaskHandler.java | ✅ 处理成功即 markSuccess |
| TaskHandler.java / TaskPayload.java | ✅ 分派接缝 + 消息体与 §3.4 契约一致 |

**plugin（9）**

| 文件 | 结论 |
|---|---|
| Tool.java / ToolPlugin.java / ToolMeta.java | ✅ 注解注册契约（name/description/parameterSchema）；轻量版参数约束（0/1 个 String）显式校验并 WARN |
| ToolRegistry.java | ✅ 启动扫描 + Map 注册 + 重复名告警；⚠️ F16（反射取 bean.getClass()，AOP 代理下注解丢失） |
| PluginExecutorImpl.java | ✅ 2s 超时 + 异常文本回传模型自愈；⚠️ F11（cachedThreadPool 无上限，无外部取消入口） |
| PluginController.java | ✅ 恰好 3 条（t7 实测） |
| CalculatorTool.java | ✅ 双栈求值器正确（含一元负号/取模/括号不匹配报错/除零判定）；⚠️ F7（javadoc 声称"优先 Nashorn 兼容引擎"，代码里只有未用的 javax.script import 与纯自写求值器） |
| DateTimeTool.java | ✅ 当前时间/日期差；正则取字段对缺参有兜底 |
| KnowledgeSearchTool.java | ✅ 复用 RagService 关键词兜底，ObjectProvider 破环 |

**rag（11）**

| 文件 | 结论 |
|---|---|
| splitter/DocSplitter.java | ✅ 段落聚合 + 硬切 + overlap 回填；chunk-size/overlap 可配（D33 落点） |
| splitter/ChatSplitter.java | ✅ **最强差异化**：时间窗口(5min) + 超长封口 + speaker/msg_time 元数据；⚠️ F5（javadoc 承诺"问答配对"，实现只有窗口+长度） |
| splitter/SplitChunk.java | ✅ |
| embed/KbEmbeddingModel.java | ✅ 接缝；dimension() 无生产调用方（F8） |
| embed/MockEmbeddingModel.java | ✅ 确定性 bigram+hash+符号位降偏置+L2（可复现、可测试，面试可讲） |
| embed/OpenAiCompatEmbeddingModel.java | ✅ langchain4j 接入；embedAll 未被使用（F8，保留合理） |
| embed/VectorCodec.java | ✅ 大端 float32 + Base64 + 余弦（归一化后=点积） |
| store/VectorStore.java / RetrievedChunk.java | ✅ 抽象合理；snippet 200 字符截断 |
| store/DbFlatVectorStore.java | ✅ 版本化缓存 + 逻辑删除文档不参与检索 + 阈值截断 + 低分回退 1 条并 WARN（D39/D41 落点） |
| prompt/PromptBuilder.java | ✅ system 强约束（只依据资料/无据不答/[n] 标注/历史冲突以资料为准）+ 历史裁剪，控幻觉主防线 |

**redis（5）**

| 文件 | 结论 |
|---|---|
| RedisClientFactory.java | ✅ 连接池 + 启动 ping 探测；⚠️ F6（markUnavailable 为**单向闩锁**，Redis 恢复后不再重探 → 此后限流永久放行）；F8（markAvailable/timeout 无调用方） |
| RateLimiter.java | ✅ **Lua 令牌桶三合一原子**（补令牌/比较/扣减），首访 nil 判定正确，TTL 兜底，故障降级放行 + WARN；✅ 双维度任一超限即 429 |
| RateLimitProperties.java | ✅ 双维度独立参数；⚠️ F6（trustForwardedFor 默认 true） |
| ClientIpResolver.java | ✅ IPv6 loopback 归一 + 无 Web 上下文返回 null；⚠️ F6 附注（多级代理取 XFF 首段） |
| ConversationMemory.java | ✅ RPUSH+LTRIM+TTL30min；Redis 不可用→空历史并回落 DB 历史（记忆是增强不是必需） |

**service（6）+ dto（4）+ impl（4）**

| 文件 | 结论 |
|---|---|
| service/DocService.java | ✅ 接口契约清晰（含给聊天模块的交接说明） |
| service/TaskService.java | ✅ 状态机语义完整；⚠️ F17（limit 无上界） |
| service/RagService.java | ✅ 向量为主 + 关键词兜底 |
| service/ChatService.java | ✅ 兼容方法保留；⚠️ F18（javadoc 说 clientIp=null 跳过该维度，实现是自动解析请求 IP） |
| service/TextExtractor.java | ✅ 三格式 + UTF-8/GBK 兜底 + DOCX 表格；normalizeMarkdown 未接线（F8） |
| service/TaskOpsEndpoint.java | ✅ 只读观测四端点；路径选 /api/ops/** 规避与 /api/tasks/{taskId} 的字面量歧义（决策正确） |
| dto/ChatRequestDto.java | ✅ |
| dto/ChatAnswerDto.java | ✅ 结构对齐 §3.1；rateLimited 字段死代码（F8，429 由异常路径返回） |
| dto/UploadResult.java | ✅ 显式回传 taskStatus，异步语义自解释 |
| dto/ChunkView.java | ✅ 刻意不回传向量（合理取舍） |
| impl/DocServiceImpl.java | ⚠️ 上传/幂等/落盘/异步导入整体正确，但 **F1（删除后重传 500）**；F19（落盘早于提交，回滚留孤儿文件） |
| impl/TaskServiceImpl.java | ✅ 全部状态流转用带条件 UPDATE（并发安全）；recordRetry 的终态保护 ne SUCCESS/ne FAILED 正确 |
| impl/RagServiceImpl.java | ✅ |
| impl/ChatServiceImpl.java | ✅ 链路完整（限流→记忆→检索→Prompt→模型→3 轮工具→落库→引用）；⚠️ F2 / F20（ensureConversation 竞态）/ F11 |

**chatlog（4，t6）**

| 文件 | 结论 |
|---|---|
| ChatImportController.java | ✅ 字段兼容 ISO/空格时间；⚠️ F12（List<Map> 无校验，畸形 JSON → 500 而非 400） |
| ChatImportService.java | ✅ **脱敏在入库前**（落盘/切分/向量化看到的都是遮蔽文本），与 §3.7 一致 |
| ChatImportTaskHandler.java | ✅ 复用同一条 MQ 流水线 + 复用 persistChunks；markSuccess 补齐（t6 自查缺陷已修） |
| SensitiveDataMasker.java | ✅ 规则常量集中、长→短顺序（身份证→手机号→邮箱）避免误替 |

### 2.2 资源与脚本（t4/t5/t6）

| 文件 | 结论 |
|---|---|
| resources/application.yml | ✅ §5 全键位 + 注释解释 app.mq.enabled 归一化；默认 H2/mock/memory |
| resources/application-mysql.yml | ✅ 可选 profile，密码走 env 占位 |
| resources/schema-h2.sql | ✅ 五表 + uk_documents_user_md5 + 幂等 IF NOT EXISTS；**F1 的约束来源** |
| resources/schema-mysql.sql | ✅ 与 H2 同构（索引内联） |
| resources/META-INF/spring.factories | ✅ 已核实**打进 fat jar 根**（非 BOOT-INF/classes），EnvironmentPostProcessor 才能被 Boot 加载；类名全 ASCII，规避中文 cmd 编码坑 |
| pom.xml | ✅ 贴合实测组合（Boot 3.5.5 / langchain4j 1.16.3 / jedis 5.0.2 / rocketmq 5.3.0 / mybatis-plus 3.5.9 / POI 5.2.5）；无 postgres/pgvector/spring-ai/docker；⚠️ F21（传递引入 rocketmq-rocksdb 28.6MB，fat jar 122.5MB） |
| run.cmd | ✅ 纯 ASCII（.cmd 在 GBK 代码页下会误解析中文，注释已解释）；JAVA_HOME 兜底 + jar 缺失提示 + 参数透传 |
| jmeter/qa-load.jmx | ✅ 双线程组 + 200 断言 + 全参数 CLI 可覆盖（#79 场景可讲） |
| jmeter/README.md | ✅ 明确"数字留空用户自测"（诚实边界） |
| data/demo-chat.json | ✅ 含三类敏感样本，供脱敏验证；⚠️ F22（位置在工程根 data/，TASKS.md §2 写的是 src/main/resources/data/） |

**fat jar 组成抽查（自行解包核对，非运行）**：426 entries / BOOT-INF/lib 163 个 / 业务 class 与资源 135 个 / 应用自身代码仅 0.15MB；jackson 全家为 Boot 管理的 **2.19.2**，与 langchain4j 1.16.3 引用的 Jackson API 符号逐一比对**无缺失方法/类型**（排除 NoSuchMethodError 隐患）。✅

---

## 3. 问题清单（按 severity，含 requiredFix）

| id | severity | 问题 | requiredFix | 位置 |
|---|---|---|---|---|
| **F1** | **medium** | **逻辑删除后的文档无法重新导入**：logicalDelete 只把 documents.status 置 DELETED，行仍占用 uk_documents_user_md5；再次上传同一内容时 findActiveByMd5（过滤 status='ACTIVE'）查不到 → 直接 insert → 撞唯一约束 → catch 里再查 ACTIVE 仍为 null → 抛 BusinessException(500,"文档入库失败（唯一约束冲突且无法回查）")。同一 bug 在 importChatDocument 上更赤裸：**没有 catch**，异常冒到全局处理器变成"500 服务内部错误: DuplicateKeyException"。用户可复现路径：上传 → 删除 → 再上传同一文件。 | 二选一：(a) catch 到 DuplicateKey 后**不带 status 过滤**地回查该 (user_id,content_md5) 行，若为 DELETED 则**复活**（status=ACTIVE、更新 filename/size/updated_at、重建 storage 文件）并为它新建导入任务；(b) 删除时把 content_md5 重写为 DELETED:<md5> 释放唯一键。推荐 (a)，并在 importChatDocument 补同样的 catch。必须由一个机械用例覆盖：上传 → DELETE → 再上传 → 期望 200 且新任务最终 SUCCESS。**为何定 medium 而非 high**：它不在 14 条验收/演示主线上（主线只做"同文件重复上传且文档仍 ACTIVE"，该路径正确），触发需要用户先删除再重传同一内容；但它是真实的用户使用序列，故列为 t9 第 1 优先修复项。 | DocServiceImpl.java:99-114, 131-152, 183-196, 211, 339-345；schema-h2.sql:20 |
| **F2** | medium | 【待裁决点】mock 下工具结果不回填 answer：算出了 396，answer 却是"根据现有资料无法回答"。 | 首选约 3 行修复（把回灌的 tool 结果拼进 answer）；次选在 README+设计文档写明边界。二选一必须落地（详见 §1）。 | MockChatModel.java:39-55；ChatServiceImpl.java:118-121 |
| **F3** | medium | **崩溃/重启后 PENDING 任务无回收**：消费是"内存队列 + 进程内调度"，进程被杀或 broker 不可达时任务停在 PENDING/RUNNING，全仓库无 @Scheduled/扫描重投（grep 0 命中）；tasks.created_at 也无索引。追问链 #91"服务挂了任务怎么办"目前**不能声称已实现**。 | (a) README + 设计文档明确写"无定时补偿扫描，崩溃后停在 PENDING 需人工重投"；(b) 可选加分：补 @Scheduled 扫描（超 N 分钟 PENDING 且 retry_count 未超限则重投）+ 运维重投端点 + tasks(created_at) 索引。 | ImportTaskConsumer.java:64/73；TaskServiceImpl（无恢复路径）；VERIFICATION.md §8.1 |
| **F4** | medium | **交付物缺 README.md 与 设计文档.md**：TASKS.md §2 文件清单与 §7-14 明确要求 README 含快速开始 + ≥3 curl + 未实现章节；设计文档是"自建路线唯一的短板补丁"，且 F1/F2/F3/F6/F9/F11 的边界都需要它承载。 | t9 必须交付两份文档，并逐条覆盖：F1 的删除后重传行为、F2 的 mock 边界、F3 的补偿缺口、F6 的 XFF/降级说明、§6 的未实现清单。 | TASKS.md:27-28, 244 |
| **F5** | medium | **ChatSplitter 文档承诺与实现不符**：javadoc 写"问答配对（问句→回答成对聚合）"，代码只有"时间窗口 + 长度封口"，无任何 ?/？ 配对逻辑，也无"相邻同说话人成组"。面试官读代码即可发现（C23/C24 是差异化主战场，被戳穿代价高）。 | 二选一：(a) 实现配对（问句结尾为 ?/？ 时与下一条合并、不在两者之间封口）；(b) javadoc 改为"时间窗口 + 长度封口；问答配对为后续增强"。推荐 (b)+README 一句说明，零回归风险。 | ChatSplitter.java:18-22, 51-77 |
| **F6** | medium | **限流三处隐患**：① markUnavailable 是单向闩锁——Redis 恢复后不再重探，服务将**永久放行**直至重启（安全语义反转）；② kb.rate-limit.trust-forwarded-for 默认 true，直连/不可信网络下伪造该头即可换桶绕过 IP 限流（t7 §7.2 已实测 XFF=9.9.9.9 可自造桶）；③ 多级代理下取 XFF **首段**（客户端可控）。 | t9：application.yml 默认 trust-forwarded-for: false；README/设计文档写清"仅可信反代后开启"与"降级为闩锁，恢复需重启"；可选：给 RedisClientFactory 加低频重探（如 30s ping 成功后 markAvailable）。 | RateLimitProperties.java:29；application.yml:86；RedisClientFactory.java:48-57；VERIFICATION.md:117 |
| **F7** | low | CalculatorTool javadoc 声称"优先尝试 Nashorn 兼容引擎（JDK17 已移除），缺失时回落自写求值器"，但代码里只有两个未使用的 javax.script import 与纯自写 shunting-yard，**不存在引擎分支**；"JDK17 已移除"的表述本身也不准确（javax.script 仍在 java.scripting 模块，被移除的是 Nashorn 引擎）。 | 删掉两行未用 import，javadoc 改为"自带白名单四则运算求值器（双栈），不做任意代码执行（不使用 ScriptEngine/eval）"——这本来就是更好的说法。 | CalculatorTool.java:5-6, 16-18 |
| **F8** | low | 死代码/未接线（被问"这个配置真的生效吗"会答不上）：MqProperties.memory.deadLetterRetained（实际硬编码 100）、InMemoryMessageSender.getDeadLetters()、RedisClientFactory.markAvailable()/timeout()、TextExtractor.normalizeMarkdown()、ChatAnswerDto.rateLimited、KbEmbeddingModel.dimension()、spring-boot-starter-validation 依赖但全仓 0 个校验注解。 | 能删的删（deadLetterRetained / normalizeMarkdown / rateLimited / validation 依赖）；保留的补注释说明用途（dimension / markAvailable / embedAll）。 | 见各文件 |
| **F9** | medium | **规格文档 ↔ 实现 ↔ 面试话术三者不一致（踩雷风险）**：项目规格架构图与追问链 #35 讲"MySQL + Redis + PgVector + MinIO、BM25→RRF→Rerank、SSE、Docker Compose"，而 TASKS.md 禁止 PgVector（改内存扁平向量库 + H2），BM25/RRF/Rerank/Docker/SSE 均已砍。照规格背答案会被戳穿"你说的 PgVector 在哪"。另 #44（query 改写/指代消解）、#47/#48（评测与准确率数字）、#100（Prompt 注入防护）在代码中**均未实现**。 | t9 在设计文档「技术选型与对比」显式写：向量库=内存扁平库 DbFlatVectorStore，"换 Milvus/pgvector 只换 VectorStore 实现"；BM25/RRF/Rerank = 已砍，用 score-threshold + top-k + 关键词兜底替代；并在「未实现与后续规划」逐条列出 #44/#47/#48/#100/#103。 | TASKS.md:19, 86；项目规格:36-40；追问链:64,68,73,76,156 |
| **F10** | low | MqEnabledBridge 用 addFirst，优先级高于命令行参数：显式传 --kb.mq.mode=rocketmq 但 yml 里 app.mq.enabled=false 时会被静默改回 memory。 | README 写明"app.mq.enabled 优先级最高，覆盖 kb.mq.mode 与命令行"；或改为仅在未显式设置时注入。 | MqEnabledBridge.java:43-47 |
| **F11** | low | 资源/预算：PluginExecutorImpl 用 Executors.newCachedThreadPool（无上限；超时任务是 cancel(true)，被阻塞的第三方工具线程会持续堆积）；/api/chat 无请求级总预算（仅"3 轮工具 + 单次 2s"与 openai 60s 超时、maxRetries=1，无熔断/无降级小模型）。 | 文档写明防线为"轮次上限 + 单次超时 + 入口限流"并列为后续增强；余力则换有界线程池 + CallerRunsPolicy、加请求级 deadline。 | PluginExecutorImpl.java:33；ChatServiceImpl.java:62；OpenAiCompatChatModel.java:38-46 |
| **F12** | low | 请求体畸形/参数类型错误的错误路径语义：@RequestBody 解析失败（HttpMessageNotReadableException）、limit 非数字等落到兜底 handler → **500**，契约语义应为 400。 | GlobalExceptionHandler 增补 HttpMessageNotReadableException / MethodArgumentTypeMismatchException → 400 + 可读 message。 | GlobalExceptionHandler.java:36-41；ChatImportController.java:33 |
| **F13** | low | provider=openai 且 key 为空时 ChatModel/Embedding 一致回落 mock（无 bug）；但**有 key 而只切 chat 不切 embedding**：导入期新切片是真实向量，历史切片仍是 256 维 → 余弦长度不等返回 0 → 检索静默全空。 | 设计文档写明"切换 provider 需重建索引（删除文档或重放任务）"；可选：DbFlatVectorStore.load() 对维度不一致切片 WARN 一次。 | ChatProviderConfig.java:43-52；VectorCodec.java:41-43 |
| **F14** | low | GET /api/documents 直接把实体（含 storagePath 绝对路径）返回客户端，属信息外泄面。 | 加 DocumentView（去掉 storagePath）或文档注明该端点仅供调试。 | DocumentController.java:63-70 |
| **F15** | low | InMemoryMessageSender.send 在 computeIfAbsent 的映射函数里启动消费线程（已知反模式：映射函数应无副作用；一旦消费者回调 send 即自锁）。 | 把 startConsumer 移出 computeIfAbsent（先 put 再在锁外启动），或注释说明"消费者不会回调 send"。 | InMemoryMessageSender.java:83-87 |
| **F16** | low | ToolRegistry 用 bean.getClass().getMethods() 反射取注解，插件 Bean 将来被 AOP 代理（加 @Transactional/@Async）时 CGLIB 子类方法不携带注解 → 插件静默消失。 | 改用 AopUtils.getTargetClass(bean) / AopProxyUtils.ultimateTargetClass(bean)，并保留"注册数为 0 时 WARN"。 | ToolRegistry.java:40-49, 65 |
| **F17** | low | /api/ops/tasks?limit=999999 等无上界，可拖库。 | 服务端钳制（如 Math.min(limit, 200)）。 | TaskServiceImpl.java:150-166 |
| **F18** | low | ChatService#ask(request, clientIp) javadoc 说 clientIp 为 null 即"跳过 IP 维度"，实际语义是"自动从 RequestContextHolder 解析"（这才是 IP 限流真正生效的原因）；ChatController 也正是走 null 路径。 | javadoc 改为"为 null 时由 RateLimiter 自行从当前请求解析；非 Web 上下文才真正跳过"。 | ChatService.java:15-26；ChatServiceImpl.java:93 |
| **F19** | low | 上传时先落盘再提交事务，事务回滚会留下孤儿文件（磁盘泄露），无清理任务。 | 文档写明"data/files 为原始件，删除文档时由人工/规划中的清理任务回收"；或把落盘放到提交后。 | DocServiceImpl.java:117, 375-385 |
| **F20** | low | ensureConversation 是"先查后插"，并发同 conversationId 会撞 uk_conv_id → 500。 | catch DuplicateKeyException 后忽略（视为已存在）即可，两行。 | ChatServiceImpl.java:229-246 |
| **F21** | low | fat jar 122.5MB 中 rocketmq-rocksdb 单包 28.6MB（+ grpc-netty-shaded 8.9MB），与"默认内存队列"形成体量反差；TASKS.md 只禁 postgres/pgvector/spring-ai/docker，故不违规，但"为什么默认内存队列还要 100+MB 包"会被问。 | 文档一句："rocketmq-client 为可选分支实现，随包发布不裁剪；生产可 exclude 或 profile 化"。 | pom.xml:78-82 |
| **F22** | info | data/demo-chat.json 实际在工程根 data/（与 H2/日志同目录），TASKS.md §2 写的是 src/main/resources/data/；plugin 三插件在 plugin/ 而非 §2 的 plugin/impl/；§2 的 PluginExecutor.java 落地为 llm/ToolExecutor.java + plugin/PluginExecutorImpl.java（依赖倒置，理由成立）。 | t9 在设计文档回写真实包结构（或反向修 TASKS.md §2 文件清单），避免"文件清单对不上仓库"被追问。 | TASKS.md:36, 52-53 |

**阻塞性判定**：无 blocker。F1 是真实缺陷但**不在十四条机械验收的覆盖路径上**（验收只覆盖"同文件重复上传且文档仍 ACTIVE"），因此不改变 t4/t5/t6 的验收结论；它改变的是"用户真实使用序列（删除后重传）"，必须在 t9 修掉或至少在 README 写明已知限制。

---

## 4. 并发 / 降级路径专项审计

### 4.1 并发（逐条给结论）

| 场景 | 结论 |
|---|---|
| 同文件并发上传 | ✅ **安全**：唯一约束兜底 + catch(DuplicateKey) 回退幂等（F1 只在"已删行占用键"时退化，是不同路径） |
| 同消息重复投递 | ✅ **安全**：UPDATE ... WHERE status='PENDING' 单语句 CAS，无 check-then-act 窗口（t5 24 线程实测 1 winner/23 loser/0 error） |
| 失败重试与成功竞争 | ✅ recordRetry 带 ne SUCCESS/ne FAILED，不会把已成功任务重排 |
| 向量索引读写 | ✅ invalidate() 版本号 + 双检锁 + List.of() 初始不可变快照；加载过程不暴露半成品 |
| 限流判定 | ✅ Lua 内原子完成"补令牌→比较→扣减"，无 GET/SET 超发窗口 |
| 消费线程异常 | ✅ 被 try/catch 隔离，线程不死 |
| 会话创建 / 落库 | ⚠️ F20（同 conversationId 并发插入 → 500） |
| 插件反射注册 | ⚠️ F16（代理场景） |
| 内存队列建队 | ⚠️ F15（映射函数内启线程） |

### 4.2 降级路径（六条 + 缺失一条）

| 依赖失效 | 行为 | 诚实度 |
|---|---|---|
| 无 LLM key | provider 强制回落 mock + WARN | ✅ 离线可跑的根本保障 |
| RocketMQ 不可用 | 抛明确异常 → TaskPublisher 置 FAILED + failReason → /api/tasks 可查 | ✅ 不静默丢消息 |
| 内存队列满 | offer 失败 → 拒绝入队 + rejected 计数 + 任务 FAILED | ✅ 背压可见（t5 已把"静默丢消息"改成"显式拒绝"） |
| 消费失败 | 指数退避重试（可配）+ 超限死信（DB 置 FAILED + 环形缓冲 + /api/ops/mq） | ✅ |
| Redis 不可用 | 限流放行 + WARN（文案明确"服务保持可用"）；会话记忆返回空 → 回落 DB 历史 | ✅ 但 F6：单向闩锁 + 永久放行的语义必须写清 |
| 检索全部低于阈值 | 回退最高分 1 条 + WARN（避免"连引用都没有"） | ✅ 与"无据不答"由 system prompt 兜住 |
| 插件未知/超时/异常 | 错误文本作为工具结果回传模型自愈，不抛进主链路 | ✅ 符合追问链 #57 |
| **进程崩溃/重启** | ❌ **无回收**（F3） | ⚠️ 必须在文档声明 |

---

## 5. 面试视角：代码能否撑住追问链

> 判定标尺：**代码里有落点**（能打开文件讲）= ✅；**只有文档/话术、代码没有** = ⚠️（必须写成"规划中"）；**完全没有** = ❌（必须承认未实现，不许假装）。

### D 组（RAG，20 问 · 主战场）

| # | 判定 | 代码落点 / 备注 |
|---|---|---|
| 31 | ✅ | ChatServiceImpl 全链路 + 日志留痕 |
| 32 | ✅ | 离线 mock + citations 溯源本身就是 RAG 价值的证明 |
| 33 | ✅ | DocSplitter 段落聚合 + overlap 可配；无实验对比数字（文档须承认） |
| 34 / 36 | ✅ | MockEmbeddingModel dim=256 / OpenAiCompat 可切；VectorCodec.cosine 归一化=点积 |
| 35 | ⚠️ | 实现是 DbFlatVectorStore（**不是 PgVector**）→ 话术必须改成"离线可跑用内存扁平库，换 Milvus 只换实现类"（F9） |
| 37 / 38 | ⚠️ | 无 BM25/RRF，只有 KnowledgeSearchTool 关键词兜底 → 讲取舍（已砍清单） |
| 39 | ✅ | score-threshold + top-k 替代 Rerank，且有"全低分回退 1 条 + WARN"的细节可讲 |
| 40 | ✅ | top-k / score-threshold 可配 |
| 41 | ✅ | refs 空 → 明确拒答不编造（PromptBuilder system 强约束） |
| 42 | ✅ | citation 含 docId/chunkId/filename/snippet/score/refIndex ↔ answer 的 [n] 一一对应 |
| 43 | ✅ | system prompt 四条款 + temperature 0.2 + 强制引用 |
| 44 | ❌ | 无 query 改写/指代消解（F9，文档必须列为规划） |
| 45 | ✅ | ConversationMemory（Redis list + TTL 30min）+ DB 兜底 |
| 46 | ✅ | history-turns 截断（无摘要压缩，如实说） |
| 47 / 48 | ❌ | 无评测集、无准确率数字（TASKS.md §8 既定） |
| 49 | ✅ | 一个配置切换 + 无 key 强制回落 + /api/health 展示当前实现 → 本项**强证据** |
| 50 | ✅ | PromptBuilder 有明确条款；无版本迭代对比数据 |

### E 组（插件，14 问 · 差异化）

| # | 判定 | 代码落点 |
|---|---|---|
| 51 / 59 | ✅ | @ToolPlugin 扫描 + ToolCatalog/ToolExecutor 双向接缝，主流程只依赖接口 |
| 52 / 53 | ✅ | 注解 + Map 注册表 + 反射；"为什么不用 SPI"注释已成文 |
| 54 / 55 | ✅ | ToolSpec → ToolSpecification 转换（含参数 schema） |
| 56 | ✅ | **description 里的"触发词: ..."约定**是规则式选工具的落点（Prompt 工程可讲） |
| 57 | ✅ | 超时 2s / 异常 → 错误文本回传模型自愈 |
| 58 | ⚠️ | 有超时 + 轮次上限 + 白名单（注册表），**无沙箱/资源限额**（F11） |
| 60 | ✅ | 注释已明确"第三方插件 = 规划中的 SPI + ClassLoader" |
| 61 | ⚠️ | 只有轻量正则取参（无 JSON Schema 校验）→ 如实讲 |
| 62 / 63 | ✅ | MAX_TOOL_ROUNDS=3 + 单次 2s + 入口限流，三重防线在代码里 |
| 64 | ✅ | CalculatorTool 可讲透（白名单 + 双栈 + 除零/括号处理），**但需先修 F7 的文档不一致** |

### G 组（性能数字，简历守数字）

| # | 判定 | 备注 |
|---|---|---|
| 79 | ✅ | jmeter/qa-load.jmx 双场景 + 全参数 CLI，场景可讲 |
| 80–88 | ⚠️ | P99/瓶颈/慢 SQL/热 key/堆积/JVM 参数/线程池配置/耗时拆解/命中率**均无实测数字**（TASKS.md §8.4 既定，留用户自测）。**t9 文档不得替用户编数字**；另：应用侧确实缺"接口耗时分解"埋点，面试只能说方法不能给数 |

### H / I 组（相关项）

| # | 判定 | 备注 |
|---|---|---|
| 89 | ✅ | TaskPublisher(afterCommit) + 手动 ACK / RECONSUME_LATER + 投递失败落 FAILED → **全项目最有说服力的一段** |
| 90 | ✅ | 状态机 CAS 幂等（+ 唯一索引 + MD5 幂等），t5 有并发实测 |
| 91 | ❌→⚠️ | 无超时任务扫描重投（F3）→ 必须讲"规划中" |
| 95 | ✅ | traceId + 分级日志 + /api/ops 观测端点 |
| 97 | ⚠️ | 有超时/重试，无熔断、无降级小模型（F11） |
| 100 | ❌ | 无 Prompt 注入防护（导入内容原样进 Prompt）→ 文档列为规划；**这是必答项，要有话术** |
| 101 | ✅ | 入库前脱敏 + 规则常量集中（可讲"为什么先身份证后手机号"） |
| 103 / 104 | ❌ | SSE / 评测未做（已砍，如实说） |

**面试结论**：D/E 组（主战场 + 差异化）**有代码可指**，属于"能撑住"；G 组缺数字但脚本齐备（属用户侧）；最需 t9 补的是**文档层面的诚实清单**（F9），否则会出现"规格说的和仓库里的不一样"这种最致命的追问。

---

## 6. 选型与契约（TASKS.md）一致性核对

| 契约点 | 实现 | 结论 |
|---|---|---|
| §0 坐标 / 主类 / JDK17 / Boot 3.5.5 | pom + KbApplication + fat jar 实测 | ✅ 一致 |
| §2 文件清单 | 88 个 Java（含 t6 的 plugin/chatlog）；3 处路径/命名偏离（F22） | ⚠️ 功能一致、清单需回写 |
| §3.1 九个 REST 接口 + {code,message,data} | 全部实现（+ /api/ops/**、/api/documents 列表等三个附加只读端点） | ✅ 超集，未破坏契约 |
| §3.2 Rag（500/50、top-k 5、threshold 0.15、[n] 格式） | 全部可配且与默认值一致 | ✅ |
| §3.3 llm 双实现 + 无 key 回落 | 一致，且 /api/health 暴露实况 | ✅ |
| §3.4 MQ（topic/payload/重试3/死信/CAS） | 一致；额外做 afterCommit 修复副作用 | ✅ 优于契约 |
| §3.5 redis（Lua 令牌桶 + 降级 + 记忆 TTL30min） | 一致；额外做双维度 | ✅ 超集 |
| §3.6 plugin（@Tool + 注册表 + 3 轮 + 2s + 3 插件） | 一致 | ✅ |
| §3.7 聊天导入（同一条 MQ 流水线 + 脱敏） | 一致（复用 IMPORT_CHAT handler） | ✅ |
| §4 五表 DDL + uk_documents_user_md5 | 两份同构且完整 | ✅（但见 F1） |
| §5 配置键总表 | 全覆盖 + rate-limit 扩展 | ✅ |
| §7 十四条验收 | 14/14 代码落点齐全；t7 实测 6/6 | ✅ |
| §7-2「单测通过」 | **仓库无 src/test，0 个测试**；mvn package（不带 -DskipTests）exit 0 但无断言 | ⚠️ 字面满足（只看 exit code），但**零单测**是明显短板；建议 t9 补 DocSplitter/ChatSplitter/CalculatorTool/VectorCodec/MockEmbeddingModel 五个纯离线单测（全确定性、无需中间件），既加固回归又是面试加分项 |
| §8 开放问题 | 均已按边界声明（PDF/RocketMQ/MySQL/压测数字/mock 质量） | ✅ |

---

## 7. 未验证项与诚实边界（承 t7，不重复执行）

1. 跨重启持久化：受沙箱受限令牌限制（t7 §1），**未验证**；H2StorageDiagnostic 已把机制讲清。
2. RocketMQ 真机收发：9876 未监听，**未验证**（仅验证"开关能选中该实现 + 不可用时诚实 FAILED"）。
3. MySQL profile 连通：root 密码未知，**未验证**。
4. JMeter P99/TPS：留用户自测。
5. openai 真实调用：无 key，**未验证**（已验证装配切换与无 key 回落）。
6. 本报告为**静态审查**：F1 的 500 复现路径由代码逐行推导（未实跑），t9 修复时必须以"上传 → 删除 → 再上传"用例复测确认。

---

## 8. 移交 t9（integration）的行动清单

**must（影响交付完整性）**

1. F4：交付 README.md（快速开始 / 构建与启动命令 / ≥3 个 curl 示例 / 架构图 / 未实现与后续规划）+ 设计文档.md（选型对比、异步导入与 RAG 与插件三条时序、异常兜底、压测章节留空待填）。
2. F1：修"删除后重传 500"，并补该路径的机械验证。
3. F3 + F6（默认值）+ F9：把边界与选型差异写进文档（含把 trust-forwarded-for 默认值改为 false）。
4. F2：二选一落地（修复 or 文档注明）。

**should（低成本高收益）**

5. F5 / F7 / F18：三处 javadoc 与实现对齐（改注释即可）。
6. F12：异常处理器补 400 分支；F17：limit 钳制。
7. §6 建议的 5 个纯单测（补齐验收 #2 的实质）。

**nice**

8. F11 有界线程池 + 请求级预算；F16 AopUtils；F20 catch DuplicateKey；F21 包体量说明。

---

*本报告为 t8 review 的正式产物；所有结论均基于仓库当前代码（88 个 Java 文件全量阅读）与 t7 的 VERIFICATION.md。*
