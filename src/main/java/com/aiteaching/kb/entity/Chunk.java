package com.aiteaching.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 切片表 chunks（含序列化向量）。 */
@TableName("chunks")
public class Chunk {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String chunkId;
    private String docId;
    private Integer seq;
    private String content;
    /** 聊天切片说话人；文档为 null */
    private String speaker;
    private LocalDateTime msgTime;
    /** float32 数组序列化（Base64） */
    private String embedding;
    private Integer dim;
    private Integer tokenCount;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSpeaker() { return speaker; }
    public void setSpeaker(String speaker) { this.speaker = speaker; }
    public LocalDateTime getMsgTime() { return msgTime; }
    public void setMsgTime(LocalDateTime msgTime) { this.msgTime = msgTime; }
    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }
    public Integer getDim() { return dim; }
    public void setDim(Integer dim) { this.dim = dim; }
    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
