package com.aiteaching.kb.mq;

/**
 * 队列消息体：{@code {taskId, taskType, docId}}（topic 固定 KB_TASK）。
 */
public class TaskPayload {

    private String taskId;
    private String taskType;
    private String docId;

    public TaskPayload() {
    }

    public TaskPayload(String taskId, String taskType, String docId) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.docId = docId;
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
}
