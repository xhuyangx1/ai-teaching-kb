package com.aiteaching.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 分片上传会话表 upload_sessions。
 *
 * <p>一次"大文件分片上传"的生命周期：{@link #STATUS_UPLOADING} → {@link #STATUS_MERGED}
 * （或 {@link #STATUS_ABORTED} 主动放弃、{@link #STATUS_EXPIRED} 超时回收）。</p>
 *
 * <p><b>为什么元数据放 DB 而不是只放 Redis</b>：会话必须跨进程重启存活，否则"断点续传"在服务
 * 重启后就退化成"从头再传"。Redis 只做加速缓存（见 {@code UploadProgressCache}），
 * <b>权威来源是 DB 行 + 磁盘分片文件</b>。</p>
 */
@TableName("upload_sessions")
public class UploadSession {

    /** 上传中：分片持续到达 */
    public static final String STATUS_UPLOADING = "UPLOADING";
    /** 已合并：全部分片收齐并成功合并为正式文档 */
    public static final String STATUS_MERGED = "MERGED";
    /** 已放弃：客户端主动 abort */
    public static final String STATUS_ABORTED = "ABORTED";
    /** 已过期：超过 TTL 未完成，分片被回收 */
    public static final String STATUS_EXPIRED = "EXPIRED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String uploadId;
    private String userId;
    private String filename;
    /** 文件总字节数（客户端声明） */
    private Long totalSize;
    /** 分片大小（字节） */
    private Integer chunkSize;
    /** 分片总数 = ceil(totalSize / chunkSize) */
    private Integer totalChunks;
    /** 客户端可选提供的整文件 MD5：提供则合并后强校验 + 支持秒传预判 */
    private String fileMd5;
    /** UPLOADING / MERGED / ABORTED / EXPIRED */
    private String status;
    /** 合并成功后关联的文档 id（status=MERGED 时非空） */
    private String mergedDocId;
    /** 合并成功后产生的导入任务 id */
    private String mergedTaskId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime expiresAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public Long getTotalSize() { return totalSize; }
    public void setTotalSize(Long totalSize) { this.totalSize = totalSize; }
    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }
    public Integer getTotalChunks() { return totalChunks; }
    public void setTotalChunks(Integer totalChunks) { this.totalChunks = totalChunks; }
    public String getFileMd5() { return fileMd5; }
    public void setFileMd5(String fileMd5) { this.fileMd5 = fileMd5; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMergedDocId() { return mergedDocId; }
    public void setMergedDocId(String mergedDocId) { this.mergedDocId = mergedDocId; }
    public String getMergedTaskId() { return mergedTaskId; }
    public void setMergedTaskId(String mergedTaskId) { this.mergedTaskId = mergedTaskId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
