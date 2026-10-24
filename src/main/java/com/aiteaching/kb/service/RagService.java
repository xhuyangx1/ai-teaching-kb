package com.aiteaching.kb.service;

import com.aiteaching.kb.rag.store.RetrievedChunk;

import java.util.List;

/** 检索服务：问题 → top-k 相关切片（含溯源信息）。 */
public interface RagService {

    List<RetrievedChunk> retrieve(String question);

    List<RetrievedChunk> retrieve(String question, int topK, double scoreThreshold);

    /** 关键词兜底检索（给 KnowledgeSearchTool 用）。 */
    List<RetrievedChunk> keywordSearch(String keyword, int limit);

    int indexSize();
}
