package com.aiteaching.kb.service.impl;

import com.aiteaching.kb.config.KbProperties;
import com.aiteaching.kb.entity.Chunk;
import com.aiteaching.kb.entity.Document;
import com.aiteaching.kb.mapper.ChunkMapper;
import com.aiteaching.kb.mapper.DocumentMapper;
import com.aiteaching.kb.rag.store.RetrievedChunk;
import com.aiteaching.kb.rag.store.VectorStore;
import com.aiteaching.kb.service.RagService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索服务实现：向量检索为主，关键词检索为兜底（用于插件 KnowledgeSearchTool）。
 */
@Service
public class RagServiceImpl implements RagService {

    private final VectorStore vectorStore;
    private final ChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final KbProperties props;

    public RagServiceImpl(VectorStore vectorStore, ChunkMapper chunkMapper, DocumentMapper documentMapper,
                          KbProperties props) {
        this.vectorStore = vectorStore;
        this.chunkMapper = chunkMapper;
        this.documentMapper = documentMapper;
        this.props = props;
    }

    @Override
    public List<RetrievedChunk> retrieve(String question) {
        return retrieve(question, props.getRag().getTopK(), props.getRag().getScoreThreshold());
    }

    @Override
    public List<RetrievedChunk> retrieve(String question, int topK, double scoreThreshold) {
        return vectorStore.search(question, topK, scoreThreshold);
    }

    @Override
    public List<RetrievedChunk> keywordSearch(String keyword, int limit) {
        List<RetrievedChunk> out = new ArrayList<>();
        if (keyword == null || keyword.isBlank()) {
            return out;
        }
        String kw = keyword.strip();
        List<Chunk> chunks = chunkMapper.selectList(new QueryWrapper<Chunk>()
                .like("content", kw)
                .orderByAsc("doc_id", "seq")
                .last("limit " + Math.max(1, limit)));
        if (chunks.isEmpty()) {
            return out;
        }
        Map<String, String> names = new HashMap<>();
        for (Document d : documentMapper.selectList(new QueryWrapper<Document>().eq("status", "ACTIVE"))) {
            names.put(d.getDocId(), d.getFilename());
        }
        int i = 1;
        for (Chunk c : chunks) {
            RetrievedChunk r = new RetrievedChunk(c.getChunkId(), c.getDocId(),
                    names.getOrDefault(c.getDocId(), c.getDocId()), c.getContent(), c.getSpeaker(), 1.0d);
            r.setRefIndex(i++);
            out.add(r);
        }
        return out;
    }

    @Override
    public int indexSize() {
        return vectorStore.size();
    }
}
