# JMeter 压测场景说明（qa-load.jmx）

> **结果数字（P99 / TPS / 错误率）由用户在目标机器自测**，本仓库只交付脚本与场景说明，
> 不包含任何结论性性能数据（见 TASKS.md §8 开放问题 4）。

## 场景

| 线程组 | 接口 | 说明 |
|---|---|---|
| chat-answer | POST /api/chat | 问答全链路：限流 → 会话记忆 → 向量检索 → mock LLM → 落库 |
| chat-import | POST /api/chats/import | 聊天记录导入：脱敏 → 落库 → MQ 异步切分向量化 |

每个线程组内置 HTTP 200 断言；chat 请求按 userId 区分（pressN），避免用户维度限流桶互相挤占。

## 运行

```bat
jmeter -n -t qa-load.jmx -JHOST=127.0.0.1 -JPORT=8080 ^
  -JCHAT_THREADS=10 -JCHAT_RAMP=10 -JCHAT_DURATION=60 ^
  -JIMPORT_THREADS=2 -JIMPORT_LOOPS=20 -l result.jtl
```

## 可配置参数（全部命令行可覆盖）

| 属性 | 默认 | 含义 |
|---|---|---|
| HOST / PORT | 127.0.0.1 / 8080 | 目标服务地址 |
| CHAT_THREADS | 10 | 问答并发线程数 |
| CHAT_RAMP | 10 | 问答线程爬坡秒数 |
| CHAT_DURATION | 60 | 问答压测持续秒数（scheduler 模式） |
| IMPORT_THREADS | 2 | 导入并发线程数（导入是异步的，并发太高只会压满队列） |
| IMPORT_RAMP | 5 | 导入线程爬坡秒数 |
| IMPORT_LOOPS | 20 | 每线程导入次数 |

## 建议观测

- 应用侧：`GET /api/ops/mq`（队列深度/死信/幂等计数）、`GET /api/ops/tasks/stats`
- 压测侧：Summary Report 的 P99/TPS；导入场景另看任务 SUCCESS/FAILED 比例
