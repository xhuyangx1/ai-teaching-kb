package com.aiteaching.kb.service;

import com.aiteaching.kb.entity.Task;

import java.util.List;
import java.util.Map;

/**
 * 任务状态机：PENDING → RUNNING → SUCCESS / FAILED。
 *
 * <p>FAILED 是终态并带 {@code failReason}，progress 为完成百分比，
 * 全流程可通过 {@code GET /api/tasks/{taskId}} 查询（含历史任务）。</p>
 */
public interface TaskService {

    /** 默认最大投递尝试次数（含首次）。 */
    int MAX_RETRY = 3;

    /** 创建 PENDING 任务并返回。 */
    Task createTask(String taskType, String docId, String payloadJson);

    Task getByTaskId(String taskId);

    List<Task> listByDocId(String docId);

    /**
     * 状态机 CAS：仅当当前为 PENDING 时置 RUNNING。
     *
     * @return 是否抢占成功（幂等消费的核心：并发重投中只有一次返回 true）
     */
    boolean markRunning(String taskId);

    void updateProgress(String taskId, int progress);

    void markSuccess(String taskId);

    /** 标记失败（终态），reason 写入 fail_reason 便于事后排查。 */
    void markFailed(String taskId, String reason);

    /**
     * 记录一次失败：retry_count+1；未超上限则回到 PENDING 等待重投，超限则置 FAILED（死信）。
     *
     * @return true 表示还会重试，false 表示已进死信
     */
    boolean recordRetry(String taskId, String reason);

    /**
     * 同 {@link #recordRetry(String, String)}，但由调用方指定尝试上限
     * （上限来自 {@code kb.mq.retry.max-attempts}，避免把重试策略硬编码在 Service 里）。
     *
     * @param maxAttempts 最大投递尝试次数（含首次），小于 1 时回退到 {@link #MAX_RETRY}
     */
    boolean recordRetry(String taskId, String reason, int maxAttempts);

    /**
     * 查询任务历史（按创建时间倒序）。
     *
     * @param status 可选状态过滤（PENDING/RUNNING/SUCCESS/FAILED），为空则不过滤
     * @param limit  返回条数上限
     */
    List<Task> listHistory(String status, int limit);

    /** 各状态任务计数（{@code GET /api/health} 观测用）。 */
    Map<String, Long> countByStatus();

    /** 仅含失败任务的精简视图（含 failReason），供排障。 */
    List<Task> listFailures(int limit);
}
