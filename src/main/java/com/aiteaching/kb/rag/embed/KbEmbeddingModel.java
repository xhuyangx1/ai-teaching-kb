package com.aiteaching.kb.rag.embed;

/**
 * 向量化抽象：embedding 实现可替换（mock 确定性伪向量 / OpenAI 兼容真实向量）。
 * "换模型不改业务代码"的接缝点。
 */
public interface KbEmbeddingModel {

    /** 把文本编码为稠密向量（mock 实现已 L2 归一化，余弦可直接用点积）。 */
    float[] embed(String text);

    /** 向量维度。 */
    int dimension();

    /** 实现名，用于日志与 /api/health 展示。 */
    String name();
}
