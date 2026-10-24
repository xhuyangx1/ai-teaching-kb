package com.aiteaching.kb.mq;

import com.aiteaching.kb.common.JsonUtil;
import com.aiteaching.kb.entity.Task;
import com.aiteaching.kb.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 任务投递入口：把"落库 + 投递"之间的时序问题收敛到一处。
 *
 * <p><b>为什么不能在事务里直接 send</b>：上传接口是 {@code @Transactional} 的，若在事务内投递，
 * 消费线程可能在事务提交前就抢到消息并回查任务行 —— 此时行还没提交，消费者查不到任务，
 * 消息被当成脏数据丢弃，任务将永远停在 PENDING。这类 bug 在高并发下才偶发，最难排查。</p>
 *
 * <p>因此这里统一改成 <b>提交后投递</b>（事务同步 {@code afterCommit}）：
 * 消费者一旦看到消息，任务行必然已可见。无事务时直接投递，行为不变。</p>
 *
 * <p>投递失败（例如 {@code kb.mq.mode=rocketmq} 但 broker 未监听）时，消息不会丢失得无声无息：
 * 记录 ERROR 并把任务置为 FAILED + failReason，通过 {@code GET /api/tasks/{taskId}} 可查。</p>
 */
@Component
public class TaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(TaskPublisher.class);

    private final MessageSender sender;
    private final TaskService taskService;

    public TaskPublisher(MessageSender sender, TaskService taskService) {
        this.sender = sender;
        this.taskService = taskService;
    }

    /** 投递任务的导入消息（topic 固定 KB_TASK）。 */
    public void publish(Task task) {
        String topic = MessageSender.TOPIC_KB_TASK;
        String payload = JsonUtil.toJson(new TaskPayload(task.getTaskId(), task.getTaskType(), task.getDocId()));

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doSend(topic, payload, task);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        // 事务回滚：任务行本身也已回滚，无需投递，避免消费者收到不存在的任务
                        log.warn("事务未提交（status={}），跳过消息投递 taskId={}", status, task.getTaskId());
                    }
                }
            });
            log.debug("已登记提交后投递 taskId={}", task.getTaskId());
        } else {
            doSend(topic, payload, task);
        }
    }

    private void doSend(String topic, String payload, Task task) {
        try {
            sender.send(topic, payload);
            log.debug("消息已投递 topic={} taskId={} mode={}", topic, task.getTaskId(), sender.mode());
        } catch (Exception e) {
            log.error("消息投递失败，任务标记 FAILED taskId={} mode={}", task.getTaskId(), sender.mode(), e);
            try {
                taskService.markFailed(task.getTaskId(), "消息投递失败(" + sender.mode() + "): " + e.getMessage());
            } catch (Exception nested) {
                log.error("标记任务失败时再次异常 taskId={}", task.getTaskId(), nested);
            }
        }
    }
}
