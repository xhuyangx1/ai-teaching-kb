package com.aiteaching.kb.llm;

import com.aiteaching.kb.common.JsonUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实对话模型：langchain4j-open-ai 1.16.3 + Function Calling。
 *
 * <p>任何 OpenAI 兼容网关（{@code kb.llm.openai.base-url}）都能接入；工具 schema 由 {@link ToolSpec}
 * 转成 {@link ToolSpecification}，因此插件模块只依赖 {@code llm.ToolSpec}，不反向依赖本类。</p>
 */
public class OpenAiCompatChatModel implements KbChatModel {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatChatModel.class);

    private final OpenAiChatModel delegate;
    private final String modelName;

    public OpenAiCompatChatModel(String baseUrl, String apiKey, String modelName) {
        this.modelName = modelName;
        this.delegate = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.2)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(1)
                .logRequests(false)
                .logResponses(false)
                .build();
        log.info("已启用 OpenAI 兼容对话模型: baseUrl={} model={}", baseUrl, modelName);
    }

    @Override
    public ChatResult chat(List<ChatMessage> messages, List<ToolSpec> tools) {
        ChatRequest.Builder builder = ChatRequest.builder().messages(messages);
        List<ToolSpecification> specs = toSpecifications(tools);
        if (!specs.isEmpty()) {
            builder.toolSpecifications(specs);
        }
        ChatResponse response = delegate.chat(builder.build());
        AiMessage ai = response.aiMessage();
        ChatResult result = new ChatResult();
        result.setContent(ai.text());
        if (ai.hasToolExecutionRequests()) {
            List<ToolCall> calls = new ArrayList<>();
            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                calls.add(new ToolCall(req.id(), req.name(), req.arguments()));
            }
            result.setToolCalls(calls);
        }
        if (response.tokenUsage() != null) {
            result.setPromptTokens(response.tokenUsage().inputTokenCount() == null ? 0 : response.tokenUsage().inputTokenCount());
            result.setCompletionTokens(response.tokenUsage().outputTokenCount() == null ? 0 : response.tokenUsage().outputTokenCount());
        }
        return result;
    }

    /** ToolSpec → langchain4j ToolSpecification（简化 JSON Schema → JsonObjectSchema）。 */
    static List<ToolSpecification> toSpecifications(List<ToolSpec> tools) {
        List<ToolSpecification> specs = new ArrayList<>();
        if (tools == null) {
            return specs;
        }
        for (ToolSpec t : tools) {
            JsonObjectSchema.Builder params = JsonObjectSchema.builder();
            Map<String, Object> schema = parseSchema(t.getParameterSchema());
            for (Map.Entry<String, Object> e : schema.entrySet()) {
                params.addProperty(e.getKey(), JsonStringSchema.builder().description(String.valueOf(e.getValue())).build());
            }
            specs.add(ToolSpecification.builder()
                    .name(t.getName())
                    .description(t.getDescription())
                    .parameters(params.build())
                    .build());
        }
        return specs;
    }

    /** 把 "{\"param\":\"说明\"}" 解析为 Map；解析失败返回空（仍然可用，只是没有参数说明）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseSchema(String json) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            return JsonUtil.fromJson(json, Map.class);
        } catch (Exception e) {
            log.debug("工具参数 schema 解析失败，忽略: {}", json);
            return out;
        }
    }

    @Override
    public String name() {
        return "openai-compat:" + modelName;
    }
}
