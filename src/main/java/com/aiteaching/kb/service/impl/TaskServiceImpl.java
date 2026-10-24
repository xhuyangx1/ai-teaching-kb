package com.aiteaching.kb.service.impl;

import com.aiteaching.kb.common.BusinessException;
import com.aiteaching.kb.entity.Task;
import com.aiteaching.kb.mapper.TaskMapper;
import com.aiteaching.kb.service.TaskService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 任务状态机实现。所有状态流转都用"带 status 条件的 UPDATE"完成，天然并发安全。
 */
@Service
public class TaskServiceImpl implements TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskServiceImpl.class);

    private final TaskMapper taskMapper;

    public TaskServiceImpl(TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    @Override
    public Task createTask(String taskType, String docId, String payloadJson) {
        Task task = new Task();
        task.setTaskId("t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        task.setTaskType(taskType);
        task.setStatus(Task.STATUS_PENDING);
        task.setDocId(docId);
        task.setPayload(payloadJson);
        task.setProgress(0);
        task.setRetryCount(0);
        LocalDateTime now = LocalDateTime.now();
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        taskMapper.insert(task);
        log.info("创建任务 taskId={} type={} docId={}", task.getTaskId(), taskType, docId);
        return task;
    }

    @Override
    public Task getByTaskId(String taskId) {
        return taskMapper.selectOne(new QueryWrapper<Task>().eq("task_id", taskId));
    }

    @Override
    public List<Task> listByDocId(String docId) {
        // 倒序：最新任务在最前，调用方取第一条即为"该文档的最近一次导入任务"
        return taskMapper.selectList(new QueryWrapper<Task>()
                .eq("doc_id", docId)
                .orderByDesc("created_at")
                .orderByDesc("id"));
    }

    @Override
    public boolean markRunning(String taskId) {
        UpdateWrapper<Task> uw = new UpdateWrapper<Task>()
                .eq("task_id", taskId)
                .eq("status", Task.STATUS_PENDING)
                .set("status", Task.STATUS_RUNNING)
                .set("updated_at", LocalDateTime.now());
        int rows = taskMapper.update(null, uw);
        if (rows == 0) {
            log.debug("CAS 未生效（任务非 PENDING）taskId={}", taskId);
            return false;
        }
        log.info("CAS 抢锁成功 taskId={} → RUNNING", taskId);
        return true;
    }

    @Override
    public void updateProgress(String taskId, int progress) {
        UpdateWrapper<Task> uw = new UpdateWrapper<Task>()
                .eq("task_id", taskId)
                .set("progress", Math.max(0, Math.min(100, progress)))
                .set("updated_at", LocalDateTime.now());
        taskMapper.update(null, uw);
    }

    @Override
    public void markSuccess(String taskId) {
        UpdateWrapper<Task> uw = new UpdateWrapper<Task>()
                .eq("task_id", taskId)
                .set("status", Task.STATUS_SUCCESS)
                .set("progress", 100)
                .set("fail_reason", null)
                .set("updated_at", LocalDateTime.now());
        taskMapper.update(null, uw);
        log.info("任务成功 taskId={}", taskId);
    }

    @Override
    public void markFailed(String taskId, String reason) {
        UpdateWrapper<Task> uw = new UpdateWrapper<Task>()
                .eq("task_id", taskId)
                .set("status", Task.STATUS_FAILED)
                .set("fail_reason", truncate(reason))
                .set("updated_at", LocalDateTime.now());
        taskMapper.update(null, uw);
        log.error("任务失败 taskId={} reason={}", taskId, reason);
    }

    @Override
    public boolean recordRetry(String taskId, String reason) {
        return recordRetry(taskId, reason, MAX_RETRY);
    }

    @Override
    public boolean recordRetry(String taskId, String reason, int maxAttempts) {
        Task task = getByTaskId(taskId);
        if (task == null) {
            throw new BusinessException(404, "任务不存在: " + taskId);
        }
        int limit = maxAttempts < 1 ? MAX_RETRY : maxAttempts;
        int retry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        // retry 是"已失败次数"：达到上限即进死信（终态 FAILED），否则回到 PENDING 等重投。
        boolean willRetry = retry < limit;
        UpdateWrapper<Task> uw = new UpdateWrapper<Task>()
                .eq("task_id", taskId)
                // 只有仍处于非终态的任务才允许被改回 PENDING，避免把并发场景下
                // 已经跑成功（或被别人置为 FAILED）的任务错误地重新排入队列。
                .ne("status", Task.STATUS_SUCCESS)
                .ne("status", Task.STATUS_FAILED)
                .set("retry_count", retry)
                .set("fail_reason", truncate(reason))
                .set("status", willRetry ? Task.STATUS_PENDING : Task.STATUS_FAILED)
                .set("updated_at", LocalDateTime.now());
        int rows = taskMapper.update(null, uw);
        if (rows == 0) {
            log.warn("任务已处于终态，忽略重试登记 taskId={}", taskId);
            return false;
        }
        log.warn("任务失败第 {} 次（上限 {}）taskId={} 将{}", retry, limit, taskId,
                willRetry ? "重投" : "进死信(FAILED)");
        return willRetry;
    }

    /** 列表查询条数上限，防止被 ?limit=999999 拖库。 */
    private static final int MAX_QUERY_LIMIT = 200;

    @Override
    public List<Task> listHistory(String status, int limit) {
        QueryWrapper<Task> qw = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq("status", status.trim().toUpperCase());
        }
        qw.orderByDesc("created_at").orderByDesc("id").last("limit " + clampLimit(limit));
        return taskMapper.selectList(qw);
    }

    @Override
    public List<Task> listFailures(int limit) {
        return taskMapper.selectList(new QueryWrapper<Task>()
                .eq("status", Task.STATUS_FAILED)
                .orderByDesc("updated_at")
                .orderByDesc("id")
                .last("limit " + clampLimit(limit)));
    }

    @Override
    public Map<String, Long> countByStatus() {
        List<Task> all = taskMapper.selectList(null);
        Map<String, Long> out = all.stream()
                .collect(Collectors.groupingBy(t -> t.getStatus() == null ? "UNKNOWN" : t.getStatus(),
                        Collectors.counting()));
        return new LinkedHashMap<>(out);
    }

    /** 把调用方传入的 limit 钳制到 [1, MAX_QUERY_LIMIT]。 */
    private static int clampLimit(int limit) {
        return Math.min(Math.max(1, limit), MAX_QUERY_LIMIT);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
