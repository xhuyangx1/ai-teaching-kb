package com.aiteaching.kb.service.impl;

import com.aiteaching.kb.common.BusinessException;
import com.aiteaching.kb.common.JsonUtil;
import com.aiteaching.kb.config.KbProperties;
import com.aiteaching.kb.entity.Conversation;
import com.aiteaching.kb.entity.Message;
import com.aiteaching.kb.llm.ChatResult;
import com.aiteaching.kb.llm.KbChatModel;
import com.aiteaching.kb.llm.ToolCall;
import com.aiteaching.kb.llm.ToolCatalog;
import com.aiteaching.kb.llm.ToolExecutor;
import com.aiteaching.kb.llm.ToolSpec;
import com.aiteaching.kb.mapper.ConversationMapper;
import com.aiteaching.kb.mapper.MessageMapper;
import com.aiteaching.kb.rag.prompt.PromptBuilder;
import com.aiteaching.kb.rag.store.RetrievedChunk;
import com.aiteaching.kb.redis.ConversationMemory;
import com.aiteaching.kb.redis.RateLimiter;
import com.aiteaching.kb.service.ChatService;
import com.aiteaching.kb.service.RagService;
import com.aiteaching.kb.service.dto.ChatAnswerDto;
import com.aiteaching.kb.service.dto.ChatRequestDto;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 问答服务实现：完整 RAG 链路。
 *
 * <pre>
 * 限流(Redis Lua) → 会话记忆 → 向量检索 top-k → Prompt(system+资料[n]) → LLM
 *   → 若有 toolCalls：执行插件 → 结果回灌模型 → 生成最终答案
 *   → 落 messages（含 citations/pluginCalls JSON）→ 返回答案 + 引用
 * </pre>
 *
 * <p>插件轮次上限（{@code MAX_TOOL_ROUNDS=3}）在这里兜住——即使插件执行器缺失或异常，
 * 也不会出现无限调用（追问链 #62：模型反复调用工具怎么防死循环）。</p>
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);
    private static final int MAX_TOOL_ROUNDS = 3;

    private final KbChatModel chatModel;
    private final RagService ragService;
    private final PromptBuilder promptBuilder;
    private final RateLimiter rateLimiter;
    private final ConversationMemory memory;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final KbProperties props;
    private final ObjectProvider<ToolCatalog> toolCatalog;
    private final ObjectProvider<ToolExecutor> toolExecutor;

    public ChatServiceImpl(KbChatModel chatModel, RagService ragService, PromptBuilder promptBuilder,
                           RateLimiter rateLimiter, ConversationMemory memory,
                           ConversationMapper conversationMapper, MessageMapper messageMapper,
                           KbProperties props, ObjectProvider<ToolCatalog> toolCatalog,
                           ObjectProvider<ToolExecutor> toolExecutor) {
        this.chatModel = chatModel;
        this.ragService = ragService;
        this.promptBuilder = promptBuilder;
        this.rateLimiter = rateLimiter;
        this.memory = memory;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.props = props;
        this.toolCatalog = toolCatalog;
        this.toolExecutor = toolExecutor;
    }

    @Override
    @Transactional
    public ChatAnswerDto ask(ChatRequestDto request, String clientIp) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new BusinessException(400, "question 不能为空");
        }
        String question = request.getQuestion().strip();
        String userId = request.getUserId() == null || request.getUserId().isBlank() ? "default" : request.getUserId();

        // 1) 限流：用户维度 + IP 维度各判一次桶，任一超限即 429（由 GlobalExceptionHandler 转成 HTTP 429）
        rateLimiter.acquire(userId, clientIp);

        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = "conv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        ensureConversation(conversationId, userId, question);

        // 2) 会话记忆（Redis 不可用时为空，功能降级但不失败）
        int turns = props.getChat().getHistoryTurns();
        List<String> history = memory.history(conversationId, turns * 2);
        if (history.isEmpty()) {
            history = loadHistoryFromDb(conversationId, turns * 2);
        }

        // 3) 向量检索
        List<RetrievedChunk> hits = ragService.retrieve(question);
        log.info("检索完成 question=[{}] hits={} indexSize={}", question, hits.size(), ragService.indexSize());

        // 4) Prompt
        String userPrompt = promptBuilder.buildUserPrompt(question, hits, history);
        List<ChatMessage> messages = new ArrayList<>(promptBuilder.toMessages(userPrompt));
        List<ToolSpec> tools = toolCatalog.getIfAvailable() == null
                ? List.of() : toolCatalog.getObject().list();

        // 5) 生成（含最多 3 轮工具调用）
        ChatResult result = chatModel.chat(messages, tools);
        List<ChatAnswerDto.PluginCallView> pluginCalls = new ArrayList<>();
        int rounds = 0;
        while (result.hasToolCalls() && rounds < MAX_TOOL_ROUNDS) {
            rounds++;
            ToolExecutor executor = toolExecutor.getIfAvailable();
            if (executor == null) {
                log.warn("模型请求了工具调用但没有可用的 ToolExecutor（插件模块未装配），跳过工具轮次");
                break;
            }
            // 先把本轮全部工具调用收集齐，再按 OpenAI 协议规定的顺序入队：
            //   ① assistant(tool_calls) 必须在前  ② 各 tool 结果紧随其后
            // 顺序反了（先 tool 结果、后 assistant）会被严格网关直接拒绝：
            //   "tool calls and tool results do not match, please start a new conversation"
            // mock 模型不校验消息顺序，所以这个缺陷只在接真实网关时才暴露。
            List<dev.langchain4j.agent.tool.ToolExecutionRequest> requests = new ArrayList<>();
            List<ToolExecutionResultMessage> toolResults = new ArrayList<>();
            for (ToolCall call : result.getToolCalls()) {
                String out;
                try {
                    out = executor.execute(call.getName(), call.getArguments());
                } catch (Exception e) {
                    // 工具失败也要回灌结果，否则网关会因为"tool_call 没有对应结果"而拒绝下一轮请求
                    out = "工具执行失败: " + e.getMessage();
                    log.warn("插件调用失败 tool={} args={} err={}", call.getName(), call.getArguments(), e.getMessage());
                }
                log.info("插件调用 tool={} args={} → {}", call.getName(), call.getArguments(), abbreviate(out));
                pluginCalls.add(ChatAnswerDto.PluginCallView.from(call, out));
                dev.langchain4j.agent.tool.ToolExecutionRequest req = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .id(call.getId() == null ? "call_" + rounds + "_" + requests.size() : call.getId())
                        .name(call.getName())
                        .arguments(call.getArguments() == null ? "{}" : call.getArguments())
                        .build();
                requests.add(req);
                toolResults.add(ToolExecutionResultMessage.from(req, out));
            }
            // ① assistant 消息（携带 tool_calls）
            messages.add(AiMessage.from(requests));
            // ② 工具结果
            messages.addAll(toolResults);
            // 显式提示"工具结果优先"，否则模型会继续按"只依据资料"的口径拒答
            // （实测：calculator 已算出 396，回答却称"资料中未涉及数学计算，无法回答"）
            List<ChatMessage> nextRound = promptBuilder.withToolResultDirective(messages);
            result = chatModel.chat(nextRound, List.of());
            messages.clear();
            messages.addAll(nextRound);
        }
        if (result.hasToolCalls()) {
            log.warn("工具调用达到上限 {} 轮，强制收敛为文本回答", MAX_TOOL_ROUNDS);
        }

        String answer = result.getContent() == null ? "" : result.getContent().strip();
        if (answer.isEmpty() && !pluginCalls.isEmpty()) {
            answer = "（模型未返回文本，插件执行结果见 pluginCalls）";
        }
        if (answer.isEmpty()) {
            answer = "根据现有资料无法回答该问题。";
        }

        // 6) 组装引用
        List<ChatAnswerDto.Citation> citations = new ArrayList<>();
        for (RetrievedChunk c : hits) {
            ChatAnswerDto.Citation cite = new ChatAnswerDto.Citation();
            cite.setRefIndex(c.getRefIndex());
            cite.setDocId(c.getDocId());
            cite.setChunkId(c.getChunkId());
            cite.setFilename(c.getFilename());
            cite.setSnippet(c.snippet());
            cite.setScore(round(c.getScore()));
            citations.add(cite);
        }

        // 7) 落库 + 记忆
        String userMsgId = persist(conversationId, "user", question, null, null, 0, 0);
        String assistantMsgId = persist(conversationId, "assistant", answer,
                JsonUtil.toJson(citations), JsonUtil.toJson(pluginCalls),
                result.getPromptTokens(), result.getCompletionTokens());
        memory.append(conversationId, "用户: " + question, turns * 2);
        memory.append(conversationId, "助手: " + abbreviate(answer), turns * 2);

        ChatAnswerDto out = new ChatAnswerDto();
        out.setConversationId(conversationId);
        out.setMessageId(assistantMsgId);
        out.setAnswer(answer);
        out.setCitations(citations);
        out.setPluginCalls(pluginCalls);
        out.setModel(chatModel.name());
        out.setPromptTokens(result.getPromptTokens());
        out.setCompletionTokens(result.getCompletionTokens());
        log.info("问答完成 conversationId={} userMsgId={} assistantMsgId={} citations={} pluginCalls={}",
                conversationId, userMsgId, assistantMsgId, citations.size(), pluginCalls.size());
        return out;
    }

    @Override
    public List<Message> history(String conversationId, int limit) {
        return messageMapper.selectList(new QueryWrapper<Message>()
                .eq("conversation_id", conversationId)
                .orderByAsc("created_at", "id")
                .last("limit " + Math.max(1, limit)));
    }

    private List<String> loadHistoryFromDb(String conversationId, int limit) {
        List<Message> recent = messageMapper.selectList(new QueryWrapper<Message>()
                .eq("conversation_id", conversationId)
                .orderByDesc("id")
                .last("limit " + Math.max(1, limit)));
        List<String> out = new ArrayList<>();
        for (int i = recent.size() - 1; i >= 0; i--) {
            Message m = recent.get(i);
            out.add(("user".equals(m.getRole()) ? "用户: " : "助手: ") + abbreviate(m.getContent()));
        }
        return out;
    }

    private void ensureConversation(String conversationId, String userId, String question) {
        Conversation existing = conversationMapper.selectOne(new QueryWrapper<Conversation>()
                .eq("conversation_id", conversationId));
        if (existing != null) {
            return;
        }
        Conversation c = new Conversation();
        c.setConversationId(conversationId);
        c.setUserId(userId);
        c.setTitle(abbreviate(question, 60));
        c.setCreatedAt(LocalDateTime.now());
        try {
            conversationMapper.insert(c);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发下同一 conversationId 可能被另一个请求先插入（先查后插的固有竞态）。
            // 会话已存在即达成目的，直接忽略，不应把 500 抛给用户。
            log.debug("会话已由并发请求创建，忽略 conversationId={}", conversationId);
        }
    }

    private String persist(String conversationId, String role, String content, String citations, String pluginCalls,
                           int promptTokens, int completionTokens) {
        Message m = new Message();
        String id = "m_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        m.setMessageId(id);
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content == null ? "" : content);
        m.setCitations(citations);
        m.setPluginCalls(pluginCalls);
        m.setPromptTokens(promptTokens);
        m.setCompletionTokens(completionTokens);
        m.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(m);
        return id;
    }

    private static double round(double v) {
        return Math.round(v * 10000d) / 10000d;
    }

    private static String abbreviate(String s) {
        return abbreviate(s, 200);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
