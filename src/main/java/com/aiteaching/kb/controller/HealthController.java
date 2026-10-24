package com.aiteaching.kb.controller;

import com.aiteaching.kb.common.ApiResponse;
import com.aiteaching.kb.config.KbProperties;
import com.aiteaching.kb.llm.KbChatModel;
import com.aiteaching.kb.mq.MessageSender;
import com.aiteaching.kb.rag.embed.KbEmbeddingModel;
import com.aiteaching.kb.redis.RateLimiter;
import com.aiteaching.kb.service.RagService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查：给启动验证脚本用（{@code GET /api/health} → 200 且 body 含 "UP"），
 * 同时暴露装配实况（当前 LLM/Embedding/MQ 实现），便于面试演示"配置切换真的生效"。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final KbChatModel chatModel;
    private final KbEmbeddingModel embeddingModel;
    private final MessageSender messageSender;
    private final RateLimiter rateLimiter;
    private final RagService ragService;
    private final KbProperties props;

    public HealthController(KbChatModel chatModel, KbEmbeddingModel embeddingModel, MessageSender messageSender,
                            RateLimiter rateLimiter, RagService ragService, KbProperties props) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.messageSender = messageSender;
        this.rateLimiter = rateLimiter;
        this.ragService = ragService;
        this.props = props;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("app", "ai-teaching-kb");
        data.put("llmProvider", props.getLlm().getProvider());
        data.put("chatModel", chatModel.name());
        data.put("embeddingModel", embeddingModel.name());
        data.put("mqMode", messageSender.mode());
        data.put("rateLimiter", rateLimiter.status());
        data.put("vectorIndexSize", ragService.indexSize());
        return ApiResponse.ok(data);
    }
}
