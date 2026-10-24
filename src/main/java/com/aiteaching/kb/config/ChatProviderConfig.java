package com.aiteaching.kb.config;

import com.aiteaching.kb.llm.KbChatModel;
import com.aiteaching.kb.llm.MockChatModel;
import com.aiteaching.kb.llm.OpenAiCompatChatModel;
import com.aiteaching.kb.rag.embed.KbEmbeddingModel;
import com.aiteaching.kb.rag.embed.MockEmbeddingModel;
import com.aiteaching.kb.rag.embed.OpenAiCompatEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM Provider 装配：{@code kb.llm.provider=mock|openai} 一个配置切换，业务代码零改动。
 *
 * <p><b>chat 与 embedding 独立配置</b>（{@code kb.llm.embedding-provider}）：实测不少
 * OpenAI 兼容网关只代理 {@code /chat/completions}，{@code /embeddings} 返回 404。
 * 绑死两者会导致"想用真实对话模型就必须放弃向量检索"；解耦后可混合运行
 * （真实模型回答 + 本地伪向量检索），RAG 全链路依然完整。</p>
 *
 * <p>安全兜底：provider=openai 但 api-key 为空时<b>强制回落 mock</b> 并打 WARN——
 * 保证"任何环境都能启动、无 key 也能跑完整链路"。</p>
 */
@Configuration
public class ChatProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatProviderConfig.class);

    @Bean
    public KbChatModel kbChatModel(KbProperties props) {
        KbProperties.Llm llm = props.getLlm();
        String provider = llm.getProvider() == null ? "mock" : llm.getProvider().trim().toLowerCase();
        if ("openai".equals(provider)) {
            String apiKey = llm.getOpenai().getApiKey();
            if (apiKey == null || apiKey.isBlank()) {
                log.warn("kb.llm.provider=openai 但未配置 API key（环境变量 OPENAI_API_KEY 为空），强制回落 mock，保证离线可跑");
                return new MockChatModel();
            }
            return new OpenAiCompatChatModel(llm.getOpenai().getBaseUrl(), apiKey, llm.getOpenai().getChatModel());
        }
        if (!"mock".equals(provider)) {
            log.warn("未知的 kb.llm.provider={}，使用 mock", provider);
        }
        return new MockChatModel();
    }

    @Bean
    public KbEmbeddingModel kbEmbeddingModel(KbProperties props) {
        KbProperties.Llm llm = props.getLlm();
        String chatProvider = llm.getProvider() == null ? "mock" : llm.getProvider().trim().toLowerCase();
        // 留空 = 跟随 chat provider（保持"一个开关切换全部"的默认体验）
        String embProvider = (llm.getEmbeddingProvider() == null || llm.getEmbeddingProvider().isBlank())
                ? chatProvider : llm.getEmbeddingProvider().trim().toLowerCase();
        String apiKey = llm.getOpenai().getApiKey();
        if ("openai".equals(embProvider) && apiKey != null && !apiKey.isBlank()) {
            log.info("向量模型：OpenAI 兼容 gateway={} model={}",
                    llm.getOpenai().getBaseUrl(), llm.getOpenai().getEmbeddingModel());
            return new OpenAiCompatEmbeddingModel(llm.getOpenai().getBaseUrl(), apiKey, llm.getOpenai().getEmbeddingModel());
        }
        if ("openai".equals(embProvider)) {
            log.warn("kb.llm.embedding-provider=openai 但 api-key 为空，向量模型回落本地 mock 实现");
        } else if (!"mock".equals(embProvider)) {
            log.warn("未知的 kb.llm.embedding-provider={}，使用本地 mock 实现", embProvider);
        }
        // 混合模式提示：chat 用真实模型而 embedding 用本地实现时，明确告知用户检索精度受限的原因
        if (!embProvider.equals(chatProvider)) {
            log.info("混合模式：对话模型={} / 向量模型=本地 bigram-hash（该网关未提供 /embeddings 时的推荐配置；"
                    + "向量检索仍可用，但语义召回精度低于真实 embedding）", chatProvider);
        }
        return new MockEmbeddingModel();
    }
}
