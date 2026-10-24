package com.aiteaching.kb.rag.store;

import java.util.List;

/**
 * 向量检索抽象。
 *
 * <p>当前实现是 {@link DbFlatVectorStore}（chunks 表全量加载 + 暴力余弦）；
 * 量级 &lt;10 万条时足够，若换 Milvus/pgvector 只需替换本接口实现，业务代码不动。</p>
 */
public interface VectorStore {

    /** 余弦 top-k 检索，并按 score-threshold 截断。 */
    List<RetrievedChunk> search(String question, int topK, double scoreThreshold);

    /** 索引进度（chunk 条数），用于 /api/health 观测。 */
    int size();

    /** 切片写入/删除后失效缓存。 */
    void invalidate();
}
