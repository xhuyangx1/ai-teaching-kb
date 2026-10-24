package com.aiteaching.kb.service.dto;

import java.util.List;

/**
 * 分片上传进度（断点续传的核心返回体）。
 *
 * <p>客户端拿到 {@link #receivedSeqs} 后<b>只补传缺失分片</b>——这是"断点续传"相比
 * "从头重传"的全部价值：网络中断/进程重启后，已传分片不白费。</p>
 */
public class UploadProgress {

    private String uploadId;
    private String filename;
    /** UPLOADING / MERGED / ABORTED / EXPIRED */
    private String status;
    private int totalChunks;
    /** 已收分片数 */
    private int receivedCount;
    /** 已收分片序号（升序）——客户端据此计算差集 */
    private List<Integer> receivedSeqs;
    /** 缺失分片序号（升序） */
    private List<Integer> missingSeqs;
    /** 已完成百分比（0-100） */
    private int percent;
    private String mergedDocId;
    private String mergedTaskId;

    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }
    public int getReceivedCount() { return receivedCount; }
    public void setReceivedCount(int receivedCount) { this.receivedCount = receivedCount; }
    public List<Integer> getReceivedSeqs() { return receivedSeqs; }
    public void setReceivedSeqs(List<Integer> receivedSeqs) { this.receivedSeqs = receivedSeqs; }
    public List<Integer> getMissingSeqs() { return missingSeqs; }
    public void setMissingSeqs(List<Integer> missingSeqs) { this.missingSeqs = missingSeqs; }
    public int getPercent() { return percent; }
    public void setPercent(int percent) { this.percent = percent; }
    public String getMergedDocId() { return mergedDocId; }
    public void setMergedDocId(String mergedDocId) { this.mergedDocId = mergedDocId; }
    public String getMergedTaskId() { return mergedTaskId; }
    public void setMergedTaskId(String mergedTaskId) { this.mergedTaskId = mergedTaskId; }
}
