package com.aiteaching.kb.llm;

import java.util.ArrayList;
import java.util.List;

/** 模型返回：文本 + 可能的工具调用 + token 统计。 */
public class ChatResult {

    private String content;
    private List<ToolCall> toolCalls = new ArrayList<>();
    private int promptTokens;
    private int completionTokens;

    public ChatResult() {
    }

    public ChatResult(String content) {
        this.content = content;
    }

    public ChatResult(String content, List<ToolCall> toolCalls) {
        this.content = content;
        this.toolCalls = toolCalls == null ? new ArrayList<>() : toolCalls;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }
    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }
}
