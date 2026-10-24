package com.aiteaching.kb.plugin;

import com.aiteaching.kb.rag.store.RetrievedChunk;
import com.aiteaching.kb.service.RagService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 内置插件 3/3：知识库关键词兜底检索。
 *
 * <p>当 RAG 主链路检索分数过低或模型想换一种问法时，模型可主动调用本工具，
 * 用 {@code {"query":"关键词"}} 通过 {@link RagService#keywordSearch} 拿到知识库片段，
 * 作为 Function Calling 的知识兜底出口。</p>
 *
 * <p>注意构造注入 {@code ObjectProvider<RagService>}：RagService 依赖向量库，
 * 若其初始化时序早于本插件也不受影响（provider 延迟解析）。</p>
 */
@ToolPlugin
public class KnowledgeSearchTool {

    private final org.springframework.beans.factory.ObjectProvider<RagService> ragProvider;

    public KnowledgeSearchTool(org.springframework.beans.factory.ObjectProvider<RagService> ragProvider) {
        this.ragProvider = ragProvider;
    }

    @Tool(name = "knowledge_search", description = "在知识库中按关键词检索资料片段，返回最相关的 top3 内容与来源。触发词: 查一下,知识库,搜一下",
            parameterSchema = "{\"query\":\"检索关键词或问题\"}")
    public String search(String argumentsJson) {
        String query = extractQuery(argumentsJson);
        if (query == null || query.isBlank()) {
            return "缺少参数 query，用法: {\"query\":\"关键词\"}";
        }
        RagService rag = ragProvider.getIfAvailable();
        if (rag == null) {
            return "检索服务暂不可用";
        }
        List<RetrievedChunk> hits = rag.keywordSearch(query, 3);
        if (hits.isEmpty()) {
            return "知识库中没有与 [" + query + "] 相关的资料";
        }
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(3, hits.size());
        for (int i = 0; i < limit; i++) {
            RetrievedChunk c = hits.get(i);
            sb.append('[').append(c.getRefIndex()).append("] ")
              .append(c.getFilename() == null ? "(未知来源)" : c.getFilename())
              .append(": ").append(c.snippet()).append('\n');
        }
        return sb.toString().strip();
    }

    static String extractQuery(String json) {
        if (json == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"query\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
