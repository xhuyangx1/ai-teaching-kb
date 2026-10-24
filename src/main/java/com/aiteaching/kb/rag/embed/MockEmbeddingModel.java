package com.aiteaching.kb.rag.embed;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认（离线）向量化实现：确定性伪向量，无需网络与 API key。
 *
 * <p>算法：中文按字、英文按词分词 → 一元 + 二元（bigram）特征 → FNV-1a 哈希到 {@code DIM} 维桶并加权累加
 * → L2 归一化。相同文本恒等输出（可复现、可测试），语义近似靠 bigram 共现；
 * 余弦相似度在"共享字词"的文本对上显著高于无关文本，足以驱动 top-k 检索链路演示。</p>
 */
public class MockEmbeddingModel implements KbEmbeddingModel {

    public static final int DIM = 256;
    private static final float UNIGRAM_WEIGHT = 1.0f;
    private static final float BIGRAM_WEIGHT = 0.6f;

    @Override
    public float[] embed(String text) {
        float[] vec = new float[DIM];
        List<String> tokens = tokenize(text);
        for (int i = 0; i < tokens.size(); i++) {
            add(vec, tokens.get(i), UNIGRAM_WEIGHT);
            if (i + 1 < tokens.size()) {
                add(vec, tokens.get(i) + "\u0001" + tokens.get(i + 1), BIGRAM_WEIGHT);
            }
        }
        l2Normalize(vec);
        return vec;
    }

    @Override
    public int dimension() {
        return DIM;
    }

    @Override
    public String name() {
        return "mock-bigram-hash-256";
    }

    private void add(float[] vec, String token, float weight) {
        int h = fnv1a(token);
        int idx = (h & 0x7fffffff) % DIM;
        // 用哈希的另一位决定符号，降低桶碰撞造成的系统性偏置
        float sign = ((h >>> 30) & 1) == 0 ? 1f : -1f;
        vec[idx] += sign * weight;
    }

    private static int fnv1a(String s) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            hash ^= s.charAt(i);
            hash *= 0x01000193;
        }
        return hash;
    }

    private static void l2Normalize(float[] vec) {
        double sum = 0d;
        for (float v : vec) {
            sum += (double) v * v;
        }
        if (sum <= 1e-9) {
            return;
        }
        float norm = (float) Math.sqrt(sum);
        for (int i = 0; i < vec.length; i++) {
            vec[i] = vec[i] / norm;
        }
    }

    /** 中文按单字、拉丁按单词切分。 */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flush(latin, tokens);
                tokens.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                latin.append(Character.toLowerCase(c));
            } else {
                flush(latin, tokens);
            }
        }
        flush(latin, tokens);
        return tokens;
    }

    private static void flush(StringBuilder latin, List<String> tokens) {
        if (latin.length() > 0) {
            tokens.add(latin.toString());
            latin.setLength(0);
        }
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
