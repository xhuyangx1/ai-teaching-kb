package com.aiteaching.kb.chatlog;

import com.aiteaching.kb.common.BusinessException;
import com.aiteaching.kb.rag.splitter.ChatSplitter;
import com.aiteaching.kb.service.DocService;
import com.aiteaching.kb.service.dto.UploadResult;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 聊天记录导入服务：JSON 数组 → 逐条<b>脱敏</b> → 序列化为文本 → 复用文档导入流水线。
 *
 * <p>职责边界：本服务只做"解析 + 脱敏 + 成文本"，切分/向量化/状态机由既有的
 * {@code IMPORT_CHAT} 任务（{@link ChatImportTaskHandler} → ChatSplitter →
 * {@link DocService#persistChunks}）完成——导入走与文档完全相同的 MQ 异步链路，
 * 不另起第二条流水线。</p>
 *
 * <p>JSON 结构（字段与 {@link ChatSplitter.ChatMessage} 对齐）：
 * {@code [{"speaker":"张老师","time":"2026-09-01 10:00:00","content":"...","type":"text"}]}</p>
 */
@Service
public class ChatImportService {

    private static final Logger log = LoggerFactory.getLogger(ChatImportService.class);

    private final DocService docService;

    public ChatImportService(DocService docService) {
        this.docService = docService;
    }

    /**
     * 导入聊天记录。
     *
     * @param records 聊天消息数组（JSON 反序列化产物）
     * @param userId  归属用户（可空，默认 default）
     */
    public UploadResult importChat(List<ChatSplitter.ChatMessage> records, String userId) {
        if (records == null || records.isEmpty()) {
            throw new BusinessException(400, "聊天记录数组为空");
        }
        // 入库前脱敏：此刻就把敏感原文替换掉，后续落盘/切分/向量化看到的都是遮蔽后的文本
        StringBuilder text = new StringBuilder();
        int masked = 0;
        for (ChatSplitter.ChatMessage m : records) {
            if (m == null || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            String safe = SensitiveDataMasker.mask(m.getContent());
            if (!safe.equals(m.getContent())) {
                masked++;
            }
            m.setContent(safe);
            text.append("speaker: ").append(m.getSpeaker() == null || m.getSpeaker().isBlank() ? "unknown" : m.getSpeaker());
            text.append(" | time: ").append(m.getTime() == null ? "" : m.getTime());
            text.append(" | type: ").append(m.getType() == null ? "text" : m.getType());
            text.append('\n').append(safe).append("\n\n");
        }
        if (text.isEmpty()) {
            throw new BusinessException(400, "聊天记录没有有效消息（content 均为空）");
        }
        log.info("聊天记录导入受理 records={} maskedMessages={}", records.size(), masked);
        return docService.importChatDocument("chat-import-" + System.currentTimeMillis() + ".json",
                text.toString(), userId);
    }

    /** 供 Controller 直接传 JSON 字符串的场景。 */
    public UploadResult importChatJson(String json, String userId) {
        List<ChatSplitter.ChatMessage> records;
        try {
            records = com.aiteaching.kb.common.JsonUtil.mapper().readValue(json,
                    new TypeReference<List<ChatSplitter.ChatMessage>>() {
                    });
        } catch (Exception e) {
            throw new BusinessException(400, "聊天记录 JSON 解析失败: " + e.getMessage());
        }
        return importChat(records, userId);
    }
}
