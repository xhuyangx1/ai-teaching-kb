package com.aiteaching.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 分片元数据表 upload_chunks：一行 = 一个已确认收到且落盘成功的分片。
 *
 * <p>唯一约束 {@code uk_upload_chunk(upload_id, seq)} 保证"同一分片重复提交 = 覆盖"，
 * 客户端重试天然幂等。<b>这张表是断点续传进度的权威来源</b>（配合磁盘分片文件双确认），
 * 不依赖客户端上报，服务重启后依然准确。</p>
 */
@TableName("upload_chunks")
public class UploadChunk {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String uploadId;
    private Integer seq;
    private Integer sizeBytes;
    private String chunkMd5;
    private LocalDateTime receivedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUploadId() { return uploadId; }
    public void setUploadId(String uploadId) { this.uploadId = uploadId; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public Integer getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Integer sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getChunkMd5() { return chunkMd5; }
    public void setChunkMd5(String chunkMd5) { this.chunkMd5 = chunkMd5; }
    public LocalDateTime getReceivedAt() { return receivedAt; }
    public void setReceivedAt(LocalDateTime receivedAt) { this.receivedAt = receivedAt; }
}
