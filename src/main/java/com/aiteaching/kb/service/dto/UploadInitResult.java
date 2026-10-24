package com.aiteaching.kb.service.dto;

/**
 * 初始化分片上传的结果。
 *
 * <p>{@link #instant} = true 表示<b>秒传命中</b>：内容已在库中，客户端无需上传任何分片，
 * 直接用 {@link #docId} 即可（与整体上传的 MD5 幂等同源）。</p>
 */
public class UploadInitResult {

    private String uploadId;
    private int chunkSize;
    private int totalChunks;
    private boolean instant;
    private String docId;
    private String message;

    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }
    public boolean isInstant() { return instant; }
    public void setInstant(boolean instant) { this.instant = instant; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
