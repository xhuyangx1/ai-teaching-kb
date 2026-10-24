package com.aiteaching.kb.controller;

import com.aiteaching.kb.common.ApiResponse;
import com.aiteaching.kb.common.BusinessException;
import com.aiteaching.kb.entity.Task;
import com.aiteaching.kb.service.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务查询接口：{@code GET /api/tasks/{taskId}} 返回 status/progress/failReason。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/{taskId}")
    public ApiResponse<Task> get(@PathVariable String taskId) {
        Task task = taskService.getByTaskId(taskId);
        if (task == null) {
            throw new BusinessException(404, "任务不存在: " + taskId);
        }
        return ApiResponse.ok(task);
    }
}
