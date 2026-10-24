package com.aiteaching.kb.mq;

import com.aiteaching.kb.common.JsonUtil;
import com.aiteaching.kb.config.MqProperties;
import com.aiteaching.kb.entity.Task;
import com.aiteaching.kb.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * 唯一消费者：解析消息 → 状态机 CAS 抢锁 → 分派给 {@link TaskHandler}。
 *
 * <h3>幂等（重复投递只生效一次）</h3>
 * <p>抢锁用一条<b>带条件的 UPDATE</b> 完成：{@code UPDATE tasks SET status='RUNNING' WHERE task_id=? AND status='PENDING'}。
 * 只有真正把 PENDING 改成 RUNNING 的那次调用会拿到 rows=1，其余并发/重投调用拿到 rows=0 直接跳过。
 * 这样即使同一条消息被投递两次（RocketMQ at-least-once 语义下的常态），业务也只会执行一次。
 * 不用"先 SELECT 判断再 UPDATE"，因为那是两步操作，存在检查与执行之间的竞态窗口。</p>
 *
 * <h3>重试与死信</h3>
 * <p>{@link #consumeWithRetry(String)} 供内存队列使用：失败后按指数退避重试，超过上限进死信
 * （任务置 FAILED 且写入 failReason，可通过 {@code GET /api/tasks/{taskId}} 查到）。
 * {@link #consume(String)} 是单次尝试版本，供真实 RocketMQ 使用——那边由 broker 负责重投，
 * 消费者只需返回"要不要重投"的信号，不要在客户端自己再套一层重试（否则重试次数是乘法关系）。</p>
 */
@Component
public class ImportTaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(ImportTaskConsumer.class);

    private final TaskService taskService;
    private final ObjectProvider<TaskHandler> handlers;
    private final MqProperties props;

    private final Deque<DeadLetter> deadLetters = new ArrayDeque<>();
    private volatile long processedCount;
    private volatile long duplicateSkippedCount;
    private volatile long deadLetterCount;

    public ImportTaskConsumer(TaskService taskService, ObjectProvider<TaskHandler> handlers, MqProperties props) {
        this.taskService = taskService;
        this.handlers = handlers;
        this.props = props;
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /**
     * 单次尝试消费（真实 MQ 用）。
     *
     * @return true 表示"本次投递已被处理或幂等跳过"（无需重投）；false 表示需要重投
     */
    public boolean consume(String payloadJson) {
        return doConsume(payloadJson) != Outcome.RETRY_NEEDED;
    }

    /**
     * 带重试与死信的消费（内存队列用）：语义与真实 MQ 对齐。
     *
     * @return true 表示最终成功或幂等跳过；false 表示已进入死信
     */
    public boolean consumeWithRetry(String payloadJson) {
        int maxAttempts = Math.max(1, props.getRetry().getMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Outcome outcome = doConsume(payloadJson);
            switch (outcome) {
                case PROCESSED, SKIPPED -> {
                    return true;
                }
                case RETRY_NEEDED -> {
                    if (attempt >= maxAttempts) {
                        break;
                    }
                    long backoff = backoffMillis(attempt);
                    log.warn("消费失败，{}ms 后进行第 {}/{} 次尝试 payload={}",
                            backoff, attempt + 1, maxAttempts, payloadJson);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        recordDeadLetter(payloadJson, "重试 " + maxAttempts + " 次仍失败");
        return false;
    }

    /** 指数退避，带上限。 */
    private long backoffMillis(int attempt) {
        long base = Math.max(1L, props.getRetry().getBackoffMs());
        long max = Math.max(base, props.getRetry().getMaxBackoffMs());
        long shift = Math.min(attempt - 1, 20);
        long value = base << shift;
        return value <= 0 || value > max ? max : value;
    }

    // ------------------------------------------------------------------
    // 核心流程
    // ------------------------------------------------------------------

    private enum Outcome {
        /** 业务处理成功。 */
        PROCESSED,
        /** 幂等跳过（终态任务 / 竞争失败 / 脏消息）。 */
        SKIPPED,
        /** 处理失败，可重试。 */
        RETRY_NEEDED
    }

    private Outcome doConsume(String payloadJson) {
        TaskPayload payload;
        try {
            payload = JsonUtil.fromJson(payloadJson, TaskPayload.class);
        } catch (Exception e) {
            // 脏消息重试多少次都一样，直接丢弃并留证
            log.error("消息体解析失败，丢弃（相当于死信）: {}", payloadJson, e);
            recordDeadLetter(payloadJson, "消息体 JSON 解析失败");
            return Outcome.SKIPPED;
        }
        if (payload == null || payload.getTaskId() == null || payload.getTaskId().isBlank()) {
            log.error("消息体缺少 taskId，丢弃: {}", payloadJson);
            recordDeadLetter(payloadJson, "消息体缺少 taskId");
            return Outcome.SKIPPED;
        }

        Task task = taskService.getByTaskId(payload.getTaskId());
        if (task == null) {
            log.warn("任务不存在（可能已被清理），丢弃消息 taskId={}", payload.getTaskId());
            recordDeadLetter(payloadJson, "任务不存在: " + payload.getTaskId());
            return Outcome.SKIPPED;
        }

        if (Task.STATUS_SUCCESS.equals(task.getStatus()) || Task.STATUS_FAILED.equals(task.getStatus())) {
            duplicateSkippedCount++;
            log.info("任务已终态，幂等跳过 taskId={} status={}", task.getTaskId(), task.getStatus());
            return Outcome.SKIPPED;
        }

        if (!taskService.markRunning(task.getTaskId())) {
            duplicateSkippedCount++;
            log.info("CAS 抢占失败（已被其他消费者抢先处理），幂等跳过 taskId={}", task.getTaskId());
            return Outcome.SKIPPED;
        }

        for (TaskHandler handler : resolveHandlers()) {
            if (handler.supports(payload.getTaskType())) {
                try {
                    task.setStatus(Task.STATUS_RUNNING);
                    handler.handle(task);
                    processedCount++;
                    return Outcome.PROCESSED;
                } catch (Exception e) {
                    log.error("任务处理失败 taskId={} type={} attempt 将计入 retry_count",
                            task.getTaskId(), payload.getTaskType(), e);
                    boolean willRetry = taskService.recordRetry(task.getTaskId(), describe(e),
                            props.getRetry().getMaxAttempts());
                    if (!willRetry) {
                        recordDeadLetter(payloadJson, "任务失败且已达重试上限: " + describe(e));
                        return Outcome.SKIPPED;
                    }
                    return Outcome.RETRY_NEEDED;
                }
            }
        }

        // 没有处理器属于"配置/契约错误"，重试也不会变好，直接失败并留证
        String reason = "没有匹配的任务处理器: " + payload.getTaskType();
        log.error("{}（taskId={}）", reason, task.getTaskId());
        taskService.markFailed(task.getTaskId(), reason);
        recordDeadLetter(payloadJson, reason);
        return Outcome.SKIPPED;
    }

    private List<TaskHandler> resolveHandlers() {
        List<TaskHandler> list = new ArrayList<>();
        handlers.forEach(list::add);
        return list;
    }

    private static String describe(Throwable e) {
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
    }

    private void recordDeadLetter(String payload, String reason) {
        deadLetterCount++;
        // 保留条数由 kb.mq.memory.dead-letter-retained 控制（此前是硬编码，配置不生效）
        int keep = Math.max(1, props.getMemory().getDeadLetterRetained());
        synchronized (deadLetters) {
            if (deadLetters.size() >= keep) {
                deadLetters.pollFirst();
            }
            deadLetters.addLast(new DeadLetter(payload, reason, System.currentTimeMillis()));
        }
    }

    // ------------------------------------------------------------------
    // 诊断
    // ------------------------------------------------------------------

    public long getProcessedCount() {
        return processedCount;
    }

    public long getDuplicateSkippedCount() {
        return duplicateSkippedCount;
    }

    public long getDeadLetterCount() {
        return deadLetterCount;
    }

    /** 最近死信快照（供 issue 排查）。 */
    public List<DeadLetter> recentDeadLetters() {
        synchronized (deadLetters) {
            return Collections.unmodifiableList(new ArrayList<>(deadLetters));
        }
    }

    /** 死信记录。 */
    public static class DeadLetter {
        private final String payload;
        private final String reason;
        private final long at;

        public DeadLetter(String payload, String reason, long at) {
            this.payload = payload;
            this.reason = reason;
            this.at = at;
        }

        public String getPayload() {
            return payload;
        }

        public String getReason() {
            return reason;
        }

        public long getAt() {
            return at;
        }
    }
}
