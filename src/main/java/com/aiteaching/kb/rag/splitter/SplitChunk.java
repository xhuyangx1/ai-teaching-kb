package com.aiteaching.kb.rag.splitter;

import java.time.LocalDateTime;

/**
 * 切分产物：内容 + 可选元数据（聊天记录才有 speaker/msgTime）。
 */
public class SplitChunk {

    private final String content;
    private final String speaker;
    private final LocalDateTime msgTime;

    public SplitChunk(String content) {
        this(content, null, null);
    }

    public SplitChunk(String content, String speaker, LocalDateTime msgTime) {
        this.content = content;
        this.speaker = speaker;
        this.msgTime = msgTime;
    }

    public String getContent() {
        return content;
    }

    public String getSpeaker() {
        return speaker;
    }

    public LocalDateTime getMsgTime() {
        return msgTime;
    }
}
