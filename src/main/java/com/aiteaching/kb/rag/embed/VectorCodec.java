package com.aiteaching.kb.rag.embed;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * 向量序列化：float32 大端字节数组 → Base64 字符串，落 chunks.embedding 列。
 */
public final class VectorCodec {

    private VectorCodec() {
    }

    public static String encode(float[] vector) {
        if (vector == null) {
            return "";
        }
        ByteBuffer buf = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.BIG_ENDIAN);
        for (float v : vector) {
            buf.putFloat(v);
        }
        return Base64.getEncoder().encodeToString(buf.array());
    }

    public static float[] decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return new float[0];
        }
        byte[] bytes = Base64.getDecoder().decode(encoded);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        float[] out = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < out.length; i++) {
            out[i] = buf.getFloat();
        }
        return out;
    }

    /** 余弦相似度；两向量假定已 L2 归一化（退化时退化为点积，仍可排序）。 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0d;
        }
        double dot = 0d;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }
}
