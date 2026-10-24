package com.aiteaching.kb.llm;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 对话模型抽象：业务代码只依赖本接口，mock / OpenAI 兼容两种实现由配置切换。
 */
public interface KbChatModel {

    ChatResult chat(List<ChatMessage> messages, List<ToolSpec> tools);

    /** 实现名，用于日志与 /api/health 展示。 */
    String name();
}
