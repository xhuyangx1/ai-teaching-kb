package com.aiteaching.kb.service.dto;

import com.aiteaching.kb.entity.Chunk;

import java.time.LocalDateTime;

/**
 * 切片视图：<b>不含向量</b>（向量是几千字符的 Base64，返回给前端没有意义），
 * 但保留 speaker/msgTime 供脱敏与切分验证。
 */
public class ChunkView {

    private String chunkId;
    private String docId;
    private int seq;
    private String content;
    private String speaker;
    private LocalDateTime msgTime;
    private int dim;
    private int tokenCount;

    public static ChunkView of(Chunk c) {
        ChunkView v = new ChunkView();
        v.chunkId = c.getChunkId();
        v.docId = c.getDocId();
        v.seq = c.getSeq() == null ? 0 : c.getSeq();
        v.content = c.getContent();
        v.speaker = c.getSpeaker();
        v.msgTime = c.getMsgTime();
        v.dim = c.getDim() == null ? 0 : c.getDim();
        v.tokenCount = c.getTokenCount() == null ? 0 : c.getTokenCount();
        return v;
    }

    public String getChunkId() { return chunkId; }
    public String getDocId() { return docId; }
    public int getSeq() { return seq; }
    public String getContent() { return content; }
    public String getSpeaker() { return speaker; }
    public LocalDateTime getMsgTime() { return msgTime; }
    public int getDim() { return dim; }
    public int getTokenCount() { return tokenCount; }
}
