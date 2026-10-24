package com.aiteaching.kb.service.dto;

import com.aiteaching.kb.llm.ToolCall;

import java.util.ArrayList;
import java.util.List;

/**
 * POST /api/chat 响应体：答案 + 引用溯源 + 插件调用记录。
 */
public class ChatAnswerDto {

    private String conversationId;
    private String messageId;
    private String answer;
    private List<Citation> citations = new ArrayList<>();
    private List<PluginCallView> pluginCalls = new ArrayList<>();
    private String model;
    private int promptTokens;
    private int completionTokens;
    /** 是否命中限流（正常响应恒为 false，429 时由异常处理返回） */
    private boolean rateLimited;

    /** 引用：docId/chunkId/snippet/score（对应答案里的 [n]）。 */
    public static class Citation {
        private int refIndex;
        private String docId;
        private String chunkId;
        private String filename;
        private String snippet;
        private double score;

        public int getRefIndex() { return refIndex; }
        public void setRefIndex(int refIndex) { this.refIndex = refIndex; }
        public String getDocId() { return docId; }
        public void setDocId(String docId) { this.docId = docId; }
        public String getChunkId() { return chunkId; }
        public void setChunkId(String chunkId) { this.chunkId = chunkId; }
        public String getFilename() { return filename; }
        public void setFilename(String filename) { this.filename = filename; }
        public String getSnippet() { return snippet; }
        public void setSnippet(String snippet) { this.snippet = snippet; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
    }

    /** 插件调用：tool/args/result。 */
    public static class PluginCallView {
        private String tool;
        private String args;
        private String result;

        public PluginCallView() {
        }

        public PluginCallView(String tool, String args, String result) {
            this.tool = tool;
            this.args = args;
            this.result = result;
        }

        public static PluginCallView from(ToolCall call, String result) {
            return new PluginCallView(call.getName(), call.getArguments(), result);
        }

        public String getTool() { return tool; }
        public void setTool(String tool) { this.tool = tool; }
        public String getArgs() { return args; }
        public void setArgs(String args) { this.args = args; }
        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }
    }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public List<Citation> getCitations() { return citations; }
    public void setCitations(List<Citation> citations) { this.citations = citations; }
    public List<PluginCallView> getPluginCalls() { return pluginCalls; }
    public void setPluginCalls(List<PluginCallView> pluginCalls) { this.pluginCalls = pluginCalls; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }
    public boolean isRateLimited() { return rateLimited; }
    public void setRateLimited(boolean rateLimited) { this.rateLimited = rateLimited; }
}
