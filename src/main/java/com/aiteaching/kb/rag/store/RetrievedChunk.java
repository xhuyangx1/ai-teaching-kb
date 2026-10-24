package com.aiteaching.kb.rag.store;

/**
 * 检索结果：切片内容 + 溯源信息（docId/chunkId/snippet/score）。
 */
public class RetrievedChunk {

    private String chunkId;
    private String docId;
    private String filename;
    private String content;
    private String speaker;
    private double score;
    /** 在 Prompt 中的引用序号（从 1 开始），与答案里的 [n] 对应。 */
    private int refIndex;

    public RetrievedChunk() {
    }

    public RetrievedChunk(String chunkId, String docId, String filename, String content, String speaker, double score) {
        this.chunkId = chunkId;
        this.docId = docId;
        this.filename = filename;
        this.content = content;
        this.speaker = speaker;
        this.score = score;
    }

    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSpeaker() { return speaker; }
    public void setSpeaker(String speaker) { this.speaker = speaker; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public int getRefIndex() { return refIndex; }
    public void setRefIndex(int refIndex) { this.refIndex = refIndex; }

    /** 引用展示用片段（前 200 字符）。 */
    public String snippet() {
        if (content == null) {
            return "";
        }
        String one = content.replace('\n', ' ');
        return one.length() <= 200 ? one : one.substring(0, 200) + "...";
    }
}
