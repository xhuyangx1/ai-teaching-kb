package com.aiteaching.kb.service.dto;

/** POST /api/chat 请求体。 */
public class ChatRequestDto {

    private String question;
    private String conversationId;
    private String userId;

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}
