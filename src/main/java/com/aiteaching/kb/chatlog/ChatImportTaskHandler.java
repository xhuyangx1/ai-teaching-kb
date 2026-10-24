package com.aiteaching.kb.chatlog;

import com.aiteaching.kb.config.KbProperties;
import com.aiteaching.kb.entity.Chunk;
import com.aiteaching.kb.entity.Document;
import com.aiteaching.kb.entity.Task;
import com.aiteaching.kb.mapper.ChunkMapper;
import com.aiteaching.kb.mq.TaskHandler;
import com.aiteaching.kb.rag.splitter.ChatSplitter;
import com.aiteaching.kb.rag.splitter.SplitChunk;
import com.aiteaching.kb.service.DocService;
import com.aiteaching.kb.service.TaskService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IMPORT_CHAT 任务处理器：读原文 → 解析回消息 → {@link ChatSplitter} 切分
 * （speaker/msg_time 进 chunk 元数据）→ 复用 {@link DocService#persistChunks} 向量化入库。
 *
 * <p>消费者与状态机零改动（t4 留的 {@code TaskHandler} 接缝）。原文是导入时已脱敏的
 * 序列化文本，这里按 {@code speaker: x | time: t | type: y} 头解析回消息列表。</p>
 */
@Component
public class ChatImportTaskHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatImportTaskHandler.class);
    private static final Pattern HEADER = Pattern.compile(
            "speaker: (.*?) \\| time: (.*?) \\| type: (.*?)\\n");

    private final DocService docService;
    private final TaskService taskService;
    private final ChatSplitter chatSplitter;
    private final ChunkMapper chunkMapper;
    private final KbProperties props;

    public ChatImportTaskHandler(DocService docService, TaskService taskService,
                                 ChatSplitter chatSplitter, ChunkMapper chunkMapper, KbProperties props) {
        this.docService = docService;
        this.taskService = taskService;
        this.chatSplitter = chatSplitter;
        this.chunkMapper = chunkMapper;
        this.props = props;
    }

    @Override
    public boolean supports(String taskType) {
        return Task.TYPE_IMPORT_CHAT.equals(taskType);
    }

    @Override
    public void handle(Task task) throws Exception {
        String docId = task.getDocId();
        Document doc = docService.getByDocId(docId);
        if (doc == null) {
            throw new IllegalStateException("任务对应的文档不存在: " + docId);
        }
        if ("DELETED".equals(doc.getStatus())) {
            log.info("文档已删除，跳过聊天导入 docId={}", docId);
            return;
        }
        taskService.updateProgress(task.getTaskId(), 25);

        String text = Files.readString(Paths.get(doc.getStoragePath()), StandardCharsets.UTF_8);
        List<ChatSplitter.ChatMessage> messages = parseMessages(text);
        if (messages.isEmpty()) {
            throw new IllegalStateException("聊天记录解析结果为空: " + doc.getStoragePath());
        }
        taskService.updateProgress(task.getTaskId(), 50);

        List<SplitChunk> chunks = chatSplitter.split(messages);
        if (chunks.isEmpty()) {
            throw new IllegalStateException("聊天记录切分结果为空");
        }
        taskService.updateProgress(task.getTaskId(), 70);
        docService.persistChunks(docId, chunks, 70);
        taskService.updateProgress(task.getTaskId(), 100);
        taskService.markSuccess(task.getTaskId());
        log.info("聊天导入完成 docId={} messages={} chunks={}", docId, messages.size(), chunks.size());
    }

    /** 把导入时序列化的文本解析回消息列表（时间格式 yyyy-MM-dd HH:mm:ss）。 */
    static List<ChatSplitter.ChatMessage> parseMessages(String text) {
        List<ChatSplitter.ChatMessage> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Matcher m = HEADER.matcher(text);
        while (m.find()) {
            String speaker = m.group(1).strip();
            String time = m.group(2).strip();
            String type = m.group(3).strip();
            int start = m.end();
            int next = text.indexOf("speaker: ", start);
            String body = next < 0 ? text.substring(start) : text.substring(start, next);
            body = body.stripTrailing();
            java.time.LocalDateTime t = null;
            if (!time.isBlank()) {
                try {
                    t = java.time.LocalDateTime.parse(time.replace(' ', 'T'));
                } catch (Exception ignore) {
                    // 时间解析失败不阻断导入，仅丢失窗口切分精度
                }
            }
            out.add(new ChatSplitter.ChatMessage(speaker, t, body, type));
        }
        return out;
    }
}
