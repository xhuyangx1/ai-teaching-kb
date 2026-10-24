package com.aiteaching.kb.mq;

import com.aiteaching.kb.entity.Task;

/**
 * 任务处理器接缝：消费者按 {@code taskType} 分派给支持它的处理器。
 *
 * <p>t4 只注册 IMPORT_DOC（{@code DocImportTaskHandler}）；
 * 后续任务类型（如 IMPORT_CHAT）只需新增一个实现类即可接入同一条流水线。</p>
 */
public interface TaskHandler {

    boolean supports(String taskType);

    /** 执行业务处理；抛出异常表示失败，由消费者重试/进死信。 */
    void handle(Task task) throws Exception;
}
