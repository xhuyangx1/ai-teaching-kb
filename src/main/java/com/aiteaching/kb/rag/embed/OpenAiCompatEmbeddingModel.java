package com.aiteaching.kb.rag.embed;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * 真实向量化：OpenAI 兼容 /v1/embeddings（langchain4j-open-ai 1.16.3）。
 *
 * <p>仅当 {@code kb.llm.provider=openai} 且配置了 api-key 时装配；任何网关只要兼容 OpenAI 协议都能用。</p>
 */
public class OpenAiCompatEmbeddingModel implements KbEmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatEmbeddingModel.class);

    private final OpenAiEmbeddingModel delegate;
    private final String modelName;

    public OpenAiCompatEmbeddingModel(String baseUrl, String apiKey, String modelName) {
        this.modelName = modelName;
        this.delegate = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(30))
                .maxRetries(1)
                .build();
        log.info("已启用 OpenAI 兼容向量模型: baseUrl={} model={}", baseUrl, modelName);
    }

    @Override
    public float[] embed(String text) {
        Response<Embedding> resp = delegate.embed(TextSegment.from(text == null ? "" : text));
        return resp.content().vector();
    }

    @Override
    public int dimension() {
        try {
            return delegate.dimension();
        } catch (Exception e) {
            log.warn("无法获取向量维度，回退 1536: {}", e.getMessage());
            return 1536;
        }
    }

    @Override
    public String name() {
        return "openai-compat:" + modelName;
    }

    /** 供批量场景使用（导入时减少往返）。 */
    public List<float[]> embedAll(List<String> texts) {
        List<TextSegment> segments = texts.stream().map(TextSegment::from).toList();
        Response<List<Embedding>> resp = delegate.embedAll(segments);
        return resp.content().stream().map(Embedding::vector).toList();
    }
}
