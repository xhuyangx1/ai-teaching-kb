package com.aiteaching.kb.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 消息表 messages（会话历史 + 引用 + 插件调用记录）。 */
@TableName("messages")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageId;
    private String conversationId;
    /** user/assistant */
    private String role;
    private String content;
    /** JSON [{chunkId,docId,snippet,score}] */
    private String citations;
    /** JSON [{tool,args,result}] */
    private String pluginCalls;
    private Integer promptTokens;
    private Integer completionTokens;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getCitations() { return citations; }
    public void setCitations(String citations) { this.citations = citations; }
    public String getPluginCalls() { return pluginCalls; }
    public void setPluginCalls(String pluginCalls) { this.pluginCalls = pluginCalls; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
