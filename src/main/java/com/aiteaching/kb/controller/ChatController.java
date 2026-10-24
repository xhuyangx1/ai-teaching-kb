package com.aiteaching.kb.controller;

import com.aiteaching.kb.common.ApiResponse;
import com.aiteaching.kb.entity.Message;
import com.aiteaching.kb.service.ChatService;
import com.aiteaching.kb.service.dto.ChatAnswerDto;
import com.aiteaching.kb.service.dto.ChatRequestDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 问答接口：{@code POST /api/chat} 走完整 RAG 链路；{@code GET /api/conversations/{id}/messages} 查历史。
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/chat")
    public ApiResponse<ChatAnswerDto> chat(@RequestBody ChatRequestDto request) {
        return ApiResponse.ok(chatService.ask(request));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<List<Message>> messages(@PathVariable String conversationId,
                                               @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(chatService.history(conversationId, limit));
    }
}
