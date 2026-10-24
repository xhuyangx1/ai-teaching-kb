package com.aiteaching.kb.service.dto;

/**
 * 初始化分片上传请求体。
 *
 * <p>{@code fileMd5} 可选：客户端若已算出整文件 MD5，服务端可据此做<b>秒传预判</b>
 * （内容已在库中则直接返回已有 docId，一个分片都不用传）。</p>
 */
public class UploadInitRequest {

    private String filename;
    private long totalSize;
    /** 分片大小（字节）。不传则用服务端默认值（kb.upload.chunk-size）。 */
    private Integer chunkSize;
    /** 整文件 MD5（可选） */
    private String fileMd5;
    private String userId;

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public long getTotalSize() { return totalSize; }
    public void setTotalSize(long totalSize) { this.totalSize = totalSize; }
    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }
    public String getFileMd5() { return fileMd5; }
    public void setFileMd5(String fileMd5) { this.fileMd5 = fileMd5; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}
