package com.aiteaching.kb.rag.prompt;

import com.aiteaching.kb.config.KbProperties;
import com.aiteaching.kb.rag.store.RetrievedChunk;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 组装：system 强约束"只依据资料回答 + 带 [n] 引用"，资料块拼接，多轮历史裁剪。
 */
@Component
public class PromptBuilder {

    public static final String SYSTEM_PROMPT = """
            你是一个严谨的教学知识库助手。请遵守以下规则：
            1. 优先依据【资料】中的内容回答问题，不得引入资料之外的知识；资料不足时必须明确回答"根据现有资料无法回答"，禁止编造。
            2. 每个来自【资料】的结论后面必须标注引用编号，格式为 [n]，n 是资料块开头的编号。
            3. 回答使用中文，先给结论再给依据，语言简洁。
            4. 若【历史对话】与当前问题冲突，以【资料】为准。
            5. 当问题需要计算、查询当前时间等【资料】无法覆盖的能力时，应当调用可用工具；工具返回的结果视为可信事实，
               直接用于回答，且此类结果不需要标注 [n] 引用（它不是来自资料）。例如工具返回"23*17+5 = 396"时，
               回答应直接给出 396，而不是回答"资料中没有"。
            6. 只有当既没有相关资料、也没有可用工具时，才回答"根据现有资料无法回答"。
            """;

    private final KbProperties props;

    public PromptBuilder(KbProperties props) {
        this.props = props;
    }

    public String buildUserPrompt(String question, List<RetrievedChunk> chunks, List<String> history) {
        StringBuilder sb = new StringBuilder();
        int turns = Math.max(0, props.getChat().getHistoryTurns());
        if (turns > 0 && history != null && !history.isEmpty()) {
            sb.append("【历史对话】\n");
            int from = Math.max(0, history.size() - turns * 2);
            for (int i = from; i < history.size(); i++) {
                sb.append(history.get(i)).append('\n');
            }
        }
        sb.append("【资料】\n");
        if (chunks == null || chunks.isEmpty()) {
            sb.append("(无)\n");
        } else {
            for (RetrievedChunk c : chunks) {
                sb.append('[').append(c.getRefIndex()).append("] (doc:")
                        .append(c.getFilename() == null ? c.getDocId() : c.getFilename()).append(") ");
                if (c.getSpeaker() != null && !c.getSpeaker().isBlank()) {
                    sb.append("(speaker:").append(c.getSpeaker()).append(") ");
                }
                sb.append(c.getContent() == null ? "" : c.getContent().strip()).append('\n');
            }
        }
        sb.append("问题：").append(question == null ? "" : question.strip());
        return sb.toString();
    }

    public List<ChatMessage> toMessages(String userPrompt) {
        return List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(userPrompt));
    }

    /**
     * 工具结果回灌轮次的补充指令。
     *
     * <p>为什么需要它：模型在首轮因为【资料】里没有答案而倾向于"无法回答"；工具结果以
     * {@code role=tool} 消息回灌后，若不显式提示"工具结果优先、且无需 [n] 引用"，
     * 模型会继续套用"只依据资料"的规则，产出"资料中未涉及…无法计算"这类自相矛盾的回答
     * （实测发生过：calculator 已算出 396，回答却说无法计算）。</p>
     */
    public static final String TOOL_RESULT_DIRECTIVE =
            "【工具结果】已在上方给出。请直接基于工具返回的结果作答，不要再以“资料中没有”为理由拒答；"
            + "工具结果不需要标注 [n] 引用。若同时有相关资料，可一并引用。";

    public List<ChatMessage> withToolResultDirective(List<ChatMessage> messages) {
        List<ChatMessage> out = new java.util.ArrayList<>(messages);
        out.add(UserMessage.from(TOOL_RESULT_DIRECTIVE));
        return out;
    }
}
