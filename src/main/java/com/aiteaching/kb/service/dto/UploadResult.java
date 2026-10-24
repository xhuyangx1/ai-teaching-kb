package com.aiteaching.kb.service.dto;

/**
 * 文档上传结果。
 *
 * <p>上传接口是<b>异步</b>的：调用返回时只保证"文件已落盘 + 文档行已提交 + 任务已创建"，
 * 切分/向量化由后台消费者完成。因此这里显式回传 {@link #taskStatus}（首次必为 PENDING），
 * 让调用方不必猜是否同步处理完了 —— 直接看这个字段，或轮询
 * {@code GET /api/tasks/{taskId}}。</p>
 */
public class UploadResult {

    private String docId;
    private String taskId;
    /** 任务当前状态：上传响应里为 PENDING；幂等命中时回带原任务的最新状态（可能是 SUCCESS）。 */
    private String taskStatus;
    private String filename;
    private String docType;
    private String md5;
    private long sizeBytes;
    /** 同 user 同 MD5 已存在时为 true（幂等命中，不重复导入、不新建任务） */
    private boolean duplicate;
    /**
     * 是否为"复活"导入：该内容之前上传过、后来被逻辑删除，本次上传把它重新置为 ACTIVE
     * 并新建了导入任务。此时 {@link #duplicate}=false（确实是新的导入），
     * 但 {@link #docId} 会沿用原文档 id（唯一约束 uk_documents_user_md5 仍被该行占用）。
     */
    private boolean revived;
    /** 异步处理提示，便于前端/脚本理解语义。 */
    private String message = "已受理，切分与向量化由后台消费者执行，可通过 GET /api/tasks/{taskId} 查询进度";

    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getTaskStatus() { return taskStatus; }
    public void setTaskStatus(String taskStatus) { this.taskStatus = taskStatus; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public String getMd5() { return md5; }
    public void setMd5(String md5) { this.md5 = md5; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public boolean isDuplicate() { return duplicate; }
    public void setDuplicate(boolean duplicate) { this.duplicate = duplicate; }
    public boolean isRevived() { return revived; }
    public void setRevived(boolean revived) { this.revived = revived; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
