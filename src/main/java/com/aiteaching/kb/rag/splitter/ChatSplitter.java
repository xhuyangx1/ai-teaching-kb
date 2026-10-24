package com.aiteaching.kb.rag.splitter;

import com.aiteaching.kb.config.KbProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 聊天记录切分器：<b>不按固定字数</b>，而是按"时间窗口 + 长度封口"聚合。
 *
 * <p>规则（与实现一致）：</p>
 * <ol>
 *   <li>按时间排序，相邻消息间隔 &gt; windowMinutes（默认 5 分钟）视为新会话片段；</li>
 *   <li>片段累计长度达到 chunk-size 时封口，避免超长 chunk 影响检索精度；</li>
 *   <li>同一时间窗口内的连续消息尽量留在同一 chunk，从而让"提问"与"回答"自然落在同一片段里
 *       —— 这是"问答不被打散"的实际机制（靠窗口聚合，而不是靠识别问句）。</li>
 * </ol>
 * <p>metadata 保留 speaker / msg_time，是追问链 C23/C24 的答案落点。</p>
 *
 * <p><b>未实现（避免过度声称）</b>：不做问句识别与显式"问答配对"——即不会因为某条消息以
 * ?/？ 结尾就强制把下一条并入或阻止封口；也不做"相邻同说话人成组"。当前策略是
 * 时间窗口 + 长度封口，配对属于后续增强项（见 README「未实现与后续规划」）。</p>
 */
@Component
public class ChatSplitter {

    /** 时间窗口（分钟）：超过即认为换了一个话题片段。 */
    private static final long WINDOW_MINUTES = 5;

    private final KbProperties props;

    public ChatSplitter(KbProperties props) {
        this.props = props;
    }

    /**
     * @param messages 已按时间排序的消息
     */
    public List<SplitChunk> split(List<ChatMessage> messages) {
        List<SplitChunk> out = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            return out;
        }
        int size = Math.max(80, props.getRag().getChunkSize());

        List<ChatMessage> group = new ArrayList<>();
        int groupLen = 0;
        LocalDateTime windowStart = null;
        LocalDateTime prevTime = null;

        for (ChatMessage m : messages) {
            if (m == null || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            LocalDateTime t = m.getTime();
            boolean newWindow = prevTime != null && t != null
                    && Duration.between(prevTime, t).toMinutes() > WINDOW_MINUTES;
            boolean tooLong = groupLen + m.getContent().length() + 1 > size;

            if (!group.isEmpty() && (newWindow || tooLong)) {
                out.add(seal(group, windowStart));
                group.clear();
                groupLen = 0;
                windowStart = null;
            }
            if (group.isEmpty()) {
                windowStart = t;
            }
            group.add(m);
            groupLen += m.getContent().length() + 1;
            prevTime = t;
        }
        if (!group.isEmpty()) {
            out.add(seal(group, windowStart));
        }
        return out;
    }

    /** 把一组消息渲染为 "speaker (time): content" 文本块，并汇总 speaker/msg_time 元数据。 */
    private SplitChunk seal(List<ChatMessage> group, LocalDateTime windowStart) {
        StringBuilder sb = new StringBuilder();
        Set<String> speakers = new LinkedHashSet<>();
        for (ChatMessage m : group) {
            String speaker = m.getSpeaker() == null || m.getSpeaker().isBlank() ? "unknown" : m.getSpeaker();
            speakers.add(speaker);
            sb.append(speaker);
            if (m.getTime() != null) {
                sb.append(" (").append(m.getTime().toString().replace('T', ' ')).append(')');
            }
            sb.append(": ").append(m.getContent().strip()).append('\n');
        }
        LocalDateTime time = windowStart;
        if (time == null) {
            for (ChatMessage m : group) {
                if (m.getTime() != null) {
                    time = m.getTime();
                    break;
                }
            }
        }
        return new SplitChunk(sb.toString().strip(), String.join(",", speakers), time);
    }

    /** 聊天消息入参（导入接口 DTO 与内部共用）。 */
    public static class ChatMessage {
        private String speaker;
        private LocalDateTime time;
        private String content;
        private String type;

        public ChatMessage() {
        }

        public ChatMessage(String speaker, LocalDateTime time, String content, String type) {
            this.speaker = speaker;
            this.time = time;
            this.content = content;
            this.type = type;
        }

        public String getSpeaker() { return speaker; }
        public void setSpeaker(String speaker) { this.speaker = speaker; }
        public LocalDateTime getTime() { return time; }
        public void setTime(LocalDateTime time) { this.time = time; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }
}
