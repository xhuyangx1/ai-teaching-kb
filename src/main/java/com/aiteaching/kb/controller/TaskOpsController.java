package com.aiteaching.kb.controller;

import com.aiteaching.kb.common.ApiResponse;
import com.aiteaching.kb.entity.Task;
import com.aiteaching.kb.mq.ImportTaskConsumer;
import com.aiteaching.kb.mq.InMemoryMessageSender;
import com.aiteaching.kb.mq.MessageSender;
import com.aiteaching.kb.redis.RateLimiter;
import com.aiteaching.kb.service.TaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务与限流的运维观测控制器（只读，不改变任何状态）。
 *
 * <p>路径统一用 {@code /api/ops/**} 前缀，而不是 {@code /api/tasks/**}，
 * 避免 {@code /api/tasks/history} 与既有 {@code /api/tasks/{taskId}} 产生字面量/变量的匹配歧义。</p>
 */
@RestController
@RequestMapping("/api/ops")
public class TaskOpsController {

    private final TaskService taskService;
    private final ImportTaskConsumer consumer;
    private final ObjectProvider<MessageSender> senderProvider;
    private final RateLimiter rateLimiter;

    public TaskOpsController(TaskService taskService, ImportTaskConsumer consumer,
                           ObjectProvider<MessageSender> senderProvider, RateLimiter rateLimiter) {
        this.taskService = taskService;
        this.consumer = consumer;
        this.senderProvider = senderProvider;
        this.rateLimiter = rateLimiter;
    }

    /** 任务历史（默认全部状态，倒序）。含 status/progress/retryCount/failReason。 */
    @GetMapping("/tasks")
    public ApiResponse<Map<String, Object>> tasks(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        List<Task> list = taskService.listHistory(status, limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", list.size());
        data.put("tasks", list);
        return ApiResponse.ok(data);
    }

    /** 仅失败任务（含 failReason），用于排障与验收"失败原因可查"。 */
    @GetMapping("/tasks/failures")
    public ApiResponse<Map<String, Object>> failures(
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        List<Task> list = taskService.listFailures(limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", list.size());
        data.put("tasks", list);
        return ApiResponse.ok(data);
    }

    /** 各状态任务计数。 */
    @GetMapping("/tasks/stats")
    public ApiResponse<Map<String, Long>> stats() {
        return ApiResponse.ok(taskService.countByStatus());
    }

    /** MQ 运行实况：模式、已入队/被拒/死信数、队列深度、幂等跳过次数、最近死信。 */
    @GetMapping("/mq")
    public ApiResponse<Map<String, Object>> mq() {
        Map<String, Object> data = new LinkedHashMap<>();
        MessageSender sender = senderProvider.getIfAvailable();
        data.put("mode", sender == null ? "none" : sender.mode());
        data.put("consumed", consumer.getProcessedCount());
        data.put("idempotentSkipped", consumer.getDuplicateSkippedCount());
        data.put("deadLetterCount", consumer.getDeadLetterCount());
        if (sender instanceof InMemoryMessageSender inMemory) {
            data.put("enqueued", inMemory.getAccepted());
            data.put("rejected", inMemory.getRejected());
            data.put("queueDepths", inMemory.queueDepths());
        }
        var recent = consumer.recentDeadLetters();
        data.put("recentDeadLetters", recent.size() <= 5 ? recent : recent.subList(recent.size() - 5, recent.size()));
        return ApiResponse.ok(data);
    }

    /** 限流实况（含 Redis 降级状态）。 */
    @GetMapping("/rate-limit")
    public ApiResponse<Map<String, Object>> rateLimit() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", rateLimiter.status());
        return ApiResponse.ok(data);
    }
}
