package com.aiteaching.kb.mq;

import com.aiteaching.kb.entity.Task;
import com.aiteaching.kb.service.DocService;
import com.aiteaching.kb.service.TaskService;
import org.springframework.stereotype.Component;

/**
 * IMPORT_DOC 任务处理器：把消费线程里的执行结果落到状态机（SUCCESS / FAILED）。
 */
@Component
public class DocImportTaskHandler implements TaskHandler {

    private final DocService docService;
    private final TaskService taskService;

    public DocImportTaskHandler(DocService docService, TaskService taskService) {
        this.docService = docService;
        this.taskService = taskService;
    }

    @Override
    public boolean supports(String taskType) {
        return Task.TYPE_IMPORT_DOC.equals(taskType);
    }

    @Override
    public void handle(Task task) {
        docService.processImport(task);
        taskService.markSuccess(task.getTaskId());
    }
}
