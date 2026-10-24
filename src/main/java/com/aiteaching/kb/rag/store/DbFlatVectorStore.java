package com.aiteaching.kb.rag.store;

import com.aiteaching.kb.config.KbProperties;
import com.aiteaching.kb.entity.Chunk;
import com.aiteaching.kb.entity.Document;
import com.aiteaching.kb.mapper.ChunkMapper;
import com.aiteaching.kb.mapper.DocumentMapper;
import com.aiteaching.kb.rag.embed.KbEmbeddingModel;
import com.aiteaching.kb.rag.embed.VectorCodec;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 扁平向量库：把 chunks 表的向量一次性加载进内存做暴力余弦扫描。
 *
 * <p>为什么这样做：本项目量级（教学文档 + 聊天记录，&lt;10 万切片）下，暴力扫描 P99 仍在毫秒级，
 * 引入向量数据库会破坏"离线可跑"约束。检索结果先按 score-threshold 截断（替代 Rerank），
 * 若全部低于阈值则回退最高分的 1 条并打 WARN —— 保证"无据不答"时不至于连引用都没有。</p>
 */
@Component
public class DbFlatVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(DbFlatVectorStore.class);

    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final KbEmbeddingModel embeddingModel;
    private final KbProperties props;

    private final AtomicLong version = new AtomicLong();
    private volatile long cachedVersion = -1L;
    private volatile List<Indexed> snapshot = List.of();

    public DbFlatVectorStore(ChunkMapper chunkMapper, DocumentMapper documentMapper,
                            KbEmbeddingModel embeddingModel, KbProperties props) {
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.embeddingModel = embeddingModel;
        this.props = props;
    }

    @Override
    public List<RetrievedChunk> search(String question, int topK, double scoreThreshold) {
        List<Indexed> index = snapshot();
        if (index.isEmpty() || question == null || question.isBlank()) {
            return List.of();
        }
        float[] q = embeddingModel.embed(question);
        List<RetrievedChunk> scored = new ArrayList<>(index.size());
        for (Indexed it : index) {
            double score = VectorCodec.cosine(q, it.vector);
            scored.add(new RetrievedChunk(it.chunkId, it.docId, it.filename, it.content, it.speaker, score));
        }
        scored.sort(Comparator.comparingDouble(RetrievedChunk::getScore).reversed());

        int k = Math.max(1, topK);
        List<RetrievedChunk> hits = new ArrayList<>();
        for (RetrievedChunk c : scored) {
            if (hits.size() >= k) {
                break;
            }
            if (c.getScore() >= scoreThreshold) {
                hits.add(c);
            }
        }
        if (hits.isEmpty() && !scored.isEmpty()) {
            RetrievedChunk best = scored.get(0);
            log.warn("检索结果全部低于阈值 {}，回退最高分切片 chunkId={} score={}",
                    scoreThreshold, best.getChunkId(), String.format("%.4f", best.getScore()));
            hits.add(best);
        }
        for (int i = 0; i < hits.size(); i++) {
            hits.get(i).setRefIndex(i + 1);
        }
        return hits;
    }

    @Override
    public int size() {
        return snapshot().size();
    }

    @Override
    public void invalidate() {
        version.incrementAndGet();
    }

    private List<Indexed> snapshot() {
        long v = version.get();
        if (cachedVersion == v) {
            return snapshot;
        }
        synchronized (this) {
            if (cachedVersion == v) {
                return snapshot;
            }
            snapshot = load();
            cachedVersion = v;
            log.info("向量索引已加载：{} 条切片（version={}）", snapshot.size(), v);
            return snapshot;
        }
    }

    private List<Indexed> load() {
        List<Document> docs = documentMapper.selectList(new QueryWrapper<Document>().eq("status", "ACTIVE"));
        Map<String, String> activeDocs = new HashMap<>();
        for (Document d : docs) {
            activeDocs.put(d.getDocId(), d.getFilename());
        }
        List<Chunk> chunks = chunkMapper.selectList(null);
        List<Indexed> out = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            String filename = activeDocs.get(c.getDocId());
            if (filename == null) {
                continue; // 文档已逻辑删除，切片不参与检索
            }
            float[] vec = VectorCodec.decode(c.getEmbedding());
            if (vec.length == 0) {
                continue;
            }
            out.add(new Indexed(c.getChunkId(), c.getDocId(), filename, c.getContent(), c.getSpeaker(), vec));
        }
        return out;
    }

    /** 内存索引条目。 */
    private static final class Indexed {
        private final String chunkId;
        private final String docId;
        private final String filename;
        private final String content;
        private final String speaker;
        private final float[] vector;

        private Indexed(String chunkId, String docId, String filename, String content, String speaker, float[] vector) {
            this.chunkId = chunkId;
            this.docId = docId;
            this.filename = filename;
            this.content = content;
            this.speaker = speaker;
            this.vector = vector;
        }
    }
}
