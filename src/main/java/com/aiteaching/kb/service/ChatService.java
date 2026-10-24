package com.aiteaching.kb.service;

import com.aiteaching.kb.entity.Message;
import com.aiteaching.kb.service.dto.ChatAnswerDto;
import com.aiteaching.kb.service.dto.ChatRequestDto;

import java.util.List;

/**
 * 问答服务：限流（用户 + IP 双维度）→ 会话记忆 → 向量检索 → Prompt → 生成（可触发插件）
 * → 落库 → 返回带引用的答案。
 */
public interface ChatService {

    /**
     * 提问。
     *
     * @param request    问题与可选的会话/用户标识
     * @param clientIp   调用方 IP，用于 IP 维度限流；可为 null（跳过该维度）
     */
    ChatAnswerDto ask(ChatRequestDto request, String clientIp);

    /** 兼容入口：不带 IP 信息，仅按用户维度限流。 */
    default ChatAnswerDto ask(ChatRequestDto request) {
        return ask(request, null);
    }

    List<Message> history(String conversationId, int limit);
}
