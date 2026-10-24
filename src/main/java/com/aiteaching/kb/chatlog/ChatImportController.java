package com.aiteaching.kb.chatlog;

import com.aiteaching.kb.common.ApiResponse;
import com.aiteaching.kb.service.dto.UploadResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 聊天记录导入接口：{@code POST /api/chats/import}。
 *
 * <p>请求体为 JSON 数组 {@code [{"speaker":"...","time":"2026-09-01 10:00:00",
 * "content":"...","type":"text"}]}；立即返回 {@code {docId, taskId, taskStatus}}
 * （PENDING），后续走同一条 MQ 异步流水线，与文档导入语义一致。</p>
 */
@RestController
@RequestMapping("/api/chats")
public class ChatImportController {

    private final ChatImportService chatImportService;

    public ChatImportController(ChatImportService chatImportService) {
        this.chatImportService = chatImportService;
    }

    /** 导入聊天记录数组（fields: speaker/time/content/type）。 */
    @PostMapping("/import")
    public ApiResponse<UploadResult> importChat(@RequestBody List<Map<String, Object>> records,
                                                @RequestParam(value = "userId", required = false) String userId) {
        List<com.aiteaching.kb.rag.splitter.ChatSplitter.ChatMessage> messages =
                new java.util.ArrayList<>(records == null ? 0 : records.size());
        if (records != null) {
            for (Map<String, Object> r : records) {
                if (r == null) {
                    continue;
                }
                String speaker = r.get("speaker") == null ? null : String.valueOf(r.get("speaker"));
                String content = r.get("content") == null ? null : String.valueOf(r.get("content"));
                String type = r.get("type") == null ? "text" : String.valueOf(r.get("type"));
                java.time.LocalDateTime time = null;
                Object t = r.get("time");
                if (t != null && !String.valueOf(t).isBlank()) {
                    try {
                        time = java.time.LocalDateTime.parse(String.valueOf(t).strip().replace(' ', 'T'));
                    } catch (Exception ignore) {
                        // 兼容 ISO 带毫秒等格式失败的情况：丢时间精度不丢消息
                        try {
                            time = java.time.OffsetDateTime.parse(String.valueOf(t).strip()).toLocalDateTime();
                        } catch (Exception ignore2) {
                            // 放弃解析
                        }
                    }
                }
                messages.add(new com.aiteaching.kb.rag.splitter.ChatSplitter.ChatMessage(speaker, time, content, type));
            }
        }
        return ApiResponse.ok(chatImportService.importChat(messages, userId));
    }
}
