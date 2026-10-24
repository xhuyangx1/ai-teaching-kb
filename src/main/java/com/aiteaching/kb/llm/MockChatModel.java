package com.aiteaching.kb.llm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认（离线）对话模型：抽取式规则实现，无 API key 也能跑完整 RAG 链路。
 *
 * <p>它不是"假装在生成"，而是诚实地做抽取：</p>
 * <ol>
 *   <li>从 Prompt 中解析出资料块 {@code [n] (doc:xxx) 内容}；</li>
 *   <li>把资料切成句子，用问题关键词（中文 2-gram + 英文单词）给句子打分；</li>
 *   <li>抽取得分最高的若干句，保留 {@code [n]} 引用标注；</li>
 *   <li>若没有任何句子命中关键词，明确回答"根据现有资料无法回答"，不编造。</li>
 * </ol>
 * <p>工具选择同样是规则式的：工具 description 里按约定写 "触发词: a,b,c"，问题命中即发起一次
 * toolCall；未写触发词时退化为 description 与问题的 token 重合度 ≥2。</p>
 *
 * <p><b>工具结果回填</b>：当框架把工具执行结果以
 * {@link ToolExecutionResultMessage} 回灌到消息列表后，本实现会把这些结果直接拼进
 * answer（形如"计算结果：23*17+5 = 396"）。否则会出现"工具明明算出了结果，
 * 用户看到的答案却说无法回答"的观感问题——验收口径要求 answer 里能直接看到工具结果。</p>
 */
public class MockChatModel implements KbChatModel {

    private static final Logger log = LoggerFactory.getLogger(MockChatModel.class);

    /** 触发词约定："触发词: 计算,算一下"。 */
    private static final Pattern TOOL_TRIGGER = Pattern.compile("触发词[:：]\\s*([^。;；]*)");
    /** 算式抽取。 */
    private static final Pattern CALC_EXPR = Pattern.compile("[-+*/×÷()\\d.\\s]{3,}");
    private static final int MAX_EXTRACTED_SENTENCES = 3;

    @Override
    public ChatResult chat(List<ChatMessage> messages, List<ToolSpec> tools) {
        String userText = lastUserText(messages);
        String question = extractQuestion(userText);
        List<Ref> refs = parseRefs(userText);

        ToolCall toolCall = pickTool(question, tools);
        if (toolCall != null) {
            log.info("MockChatModel 触发工具调用: {} args={}", toolCall.getName(), toolCall.getArguments());
            return new ChatResult("", List.of(toolCall));
        }

        // 工具结果回填：上一轮发起过 toolCall 后，框架会把执行结果以
        // ToolExecutionResultMessage 追加进消息列表，此处直接作为答案主体返回，
        // 避免"算出了结果却说无法回答"。
        String toolOutput = collectToolResults(messages);
        String answer = toolOutput == null ? answer(question, refs) : toolAnswer(toolOutput, question, refs);
        ChatResult result = new ChatResult(answer);
        result.setPromptTokens(estimateTokens(userText));
        result.setCompletionTokens(estimateTokens(answer));
        return result;
    }

    @Override
    public String name() {
        return "mock-extractive";
    }

    /**
     * 取出消息列表中最近一次工具执行结果（无则返回 null）。
     * 多条工具结果按调用顺序拼接。
     */
    static String collectToolResults(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        List<String> outputs = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m instanceof ToolExecutionResultMessage trm) {
                String text = trm.text();
                if (text != null && !text.isBlank()) {
                    outputs.add(text.strip());
                }
            }
        }
        return outputs.isEmpty() ? null : String.join("\n", outputs);
    }

    /**
     * 以工具结果为主体的回答。工具结果已经是对问题的直接答案（例如计算结果），
     * 因此置顶展示；若检索资料中还能抽出相关句子，作为补充依据附在后面。
     */
    private String toolAnswer(String toolOutput, String question, List<Ref> refs) {
        StringBuilder sb = new StringBuilder();
        sb.append("计算结果：").append(toolOutput);
        String extracted = answer(question, refs);
        // 抽取路径的"无法回答"是兜底文案，有工具结果时不应再重复出现
        if (!extracted.contains("无法回答")) {
            sb.append('\n').append(extracted);
        }
        return sb.toString();
    }

    /** 抽取式回答。 */
    private String answer(String question, List<Ref> refs) {
        if (refs.isEmpty()) {
            return "根据现有资料无法回答该问题：知识库中没有可用的资料片段。";
        }
        Set<String> keywords = keywords(question);
        List<Scored> scored = new ArrayList<>();
        for (Ref r : refs) {
            for (String sentence : sentences(r.content)) {
                scored.add(new Scored(r.index, sentence, matchScore(sentence, keywords)));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        List<Scored> picked = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Scored s : scored) {
            if (s.score <= 0) {
                break;
            }
            if (seen.add(s.ref + "|" + s.sentence)) {
                picked.add(s);
            }
            if (picked.size() >= MAX_EXTRACTED_SENTENCES) {
                break;
            }
        }
        if (picked.isEmpty()) {
            return "根据现有资料无法回答该问题：检索到的资料与问题没有直接关联（未命中问题关键词）。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("根据知识库资料，答案如下：\n");
        for (Scored s : picked) {
            sb.append("- ").append(s.sentence.strip()).append(" [").append(s.ref).append("]\n");
        }
        sb.append("（以上内容由离线 mock 模型从检索片段中抽取，方括号编号对应 citations 的 refIndex）");
        return sb.toString();
    }

    private static double matchScore(String sentence, Set<String> keywords) {
        if (keywords.isEmpty()) {
            return 0d;
        }
        int hit = 0;
        for (String kw : keywords) {
            if (sentence.contains(kw)) {
                hit++;
            }
        }
        if (hit == 0) {
            return 0d;
        }
        // 命中数与覆盖率加权，长句轻微惩罚，避免整段被抽出来
        double coverage = (double) hit / keywords.size();
        return hit + coverage * 2 - Math.min(1.0, sentence.length() / 500.0);
    }

    /** 问题关键词：中文 2-gram（过滤停用词）+ 英文/数字单词。 */
    static Set<String> keywords(String question) {
        Set<String> out = new LinkedHashSet<>();
        if (question == null) {
            return out;
        }
        String q = question.replaceAll("[\\s，。？！,.?!、：:；;（）()“”\"']", " ").strip();
        StringBuilder latin = new StringBuilder();
        List<String> runs = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                flushLatin(latin, out);
                run.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushLatin(latin, out);
                if (run.length() > 0) {
                    runs.add(run.toString());
                    run.setLength(0);
                }
                latin.append(Character.toLowerCase(c));
            } else {
                flushLatin(latin, out);
                if (run.length() > 0) {
                    runs.add(run.toString());
                    run.setLength(0);
                }
            }
        }
        flushLatin(latin, out);
        if (run.length() > 0) {
            runs.add(run.toString());
        }
        for (String r : runs) {
            if (r.length() == 1) {
                if (!STOP.contains(r)) {
                    out.add(r);
                }
                continue;
            }
            for (int i = 0; i + 2 <= r.length(); i++) {
                String g = r.substring(i, i + 2);
                if (!STOP.contains(g)) {
                    out.add(g);
                }
            }
        }
        return out;
    }

    private static void flushLatin(StringBuilder latin, Set<String> out) {
        if (latin.length() >= 2) {
            out.add(latin.toString());
        }
        latin.setLength(0);
    }

    private static final Set<String> STOP = Set.of(
            "的是", "了吗", "什么", "怎么", "如何", "请问", "一下", "这个", "那个", "我们", "可以", "是否",
            "以及", "为什", "么使", "不是", "等于", "多少");

    private ToolCall pickTool(String question, List<ToolSpec> tools) {
        if (tools == null || tools.isEmpty() || question == null || question.isBlank()) {
            return null;
        }
        ToolSpec best = null;
        int bestHit = 0;
        for (ToolSpec t : tools) {
            int hit = triggerHits(question, t);
            if (hit > bestHit) {
                bestHit = hit;
                best = t;
            }
        }
        if (best == null) {
            return null;
        }
        String args = buildArguments(best, question);
        String id = "call_" + Integer.toHexString((best.getName() + question).hashCode() & 0x7fffffff);
        return new ToolCall(id, best.getName(), args);
    }

    private int triggerHits(String question, ToolSpec tool) {
        if (tool.getDescription() != null) {
            Matcher m = TOOL_TRIGGER.matcher(tool.getDescription());
            if (m.find()) {
                int hit = 0;
                for (String kw : m.group(1).split("[,，、/]")) {
                    String k = kw.strip();
                    if (!k.isEmpty() && question.contains(k)) {
                        hit++;
                    }
                }
                return hit > 0 ? hit + 1 : 0;
            }
        }
        Set<String> qk = keywords(question);
        Set<String> dk = keywords(tool.getName() + " " + tool.getDescription());
        dk.retainAll(qk);
        return dk.size() >= 2 ? dk.size() : 0;
    }

    /** mock 参数：算式类工具抽表达式，其余传问题原文（query）。 */
    private String buildArguments(ToolSpec tool, String question) {
        String name = tool.getName() == null ? "" : tool.getName().toLowerCase();
        if (name.contains("calc") || name.contains("math")) {
            Matcher m = CALC_EXPR.matcher(question);
            String expr = null;
            while (m.find()) {
                String candidate = m.group().strip();
                if (candidate.length() >= 3 && candidate.matches(".*\\d.*")) {
                    expr = candidate;
                }
            }
            if (expr != null) {
                return "{\"expression\":\"" + expr.replace("×", "*").replace("÷", "/") + "\"}";
            }
        }
        String escaped = question.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"query\":\"" + escaped + "\"}";
    }

    private static String lastUserText(List<ChatMessage> messages) {
        if (messages == null) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m instanceof UserMessage um) {
                return um.singleText() == null ? "" : um.singleText();
            }
        }
        return "";
    }

    /** 问题取最后一个"问题："之后的内容；没有标记则用整段。 */
    static String extractQuestion(String userText) {
        if (userText == null) {
            return "";
        }
        int idx = userText.lastIndexOf("问题：");
        if (idx < 0) {
            idx = userText.lastIndexOf("问题:");
        }
        if (idx >= 0) {
            return userText.substring(idx + 3).strip();
        }
        return userText.strip();
    }

    /**
     * 解析 Prompt 中形如 {@code [1] (doc:文件名) 内容} 的资料块。
     *
     * <p>关键点：资料内容本身是<b>多行</b>的（chunk 由多个段落聚合而来），
     * 因此不能只取首行——内容要一直吃到下一个资料块标记或"问题："行为止。
     * 用行首结构判断而不是正则，避免内容里出现括号/方括号时误判。</p>
     */
    static List<Ref> parseRefs(String userText) {
        List<Ref> refs = new ArrayList<>();
        if (userText == null || userText.isEmpty()) {
            return refs;
        }
        StringBuilder current = null;
        int currentIndex = -1;
        for (String rawLine : userText.split("\\n")) {
            String line = rawLine.strip();
            Integer marker = refMarkerIndex(line);
            if (marker != null) {
                if (current != null) {
                    refs.add(new Ref(currentIndex, current.toString().strip()));
                }
                current = new StringBuilder();
                currentIndex = marker;
                String head = stripHead(line);
                if (!head.isEmpty()) {
                    current.append(head);
                }
                continue;
            }
            if (line.startsWith("问题：") || line.startsWith("问题:")) {
                break;
            }
            if (current != null) {
                if (current.length() > 0) {
                    current.append('\n');
                }
                current.append(line);
            }
        }
        if (current != null) {
            refs.add(new Ref(currentIndex, current.toString().strip()));
        }
        return refs;
    }

    /** 若该行是资料块起始行（[n] 后紧跟 (doc:...)），返回编号 n；否则返回 null。 */
    private static Integer refMarkerIndex(String line) {
        if (!line.startsWith("[")) {
            return null;
        }
        int close = line.indexOf(']');
        if (close <= 1) {
            return null;
        }
        int index;
        try {
            index = Integer.parseInt(line.substring(1, close).strip());
        } catch (NumberFormatException e) {
            return null;
        }
        String rest = line.substring(close + 1).strip();
        if (!rest.startsWith("(doc:") && !rest.startsWith("(")) {
            return null;
        }
        return rest.indexOf(')') < 0 ? null : index;
    }

    /** 去掉资料块的 [n] (doc:xxx) 头，保留剩余内容（可能还有 (speaker:xxx) 前缀）。 */
    private static String stripHead(String line) {
        int close = line.indexOf(']');
        String rest = line.substring(close + 1).strip();
        int paren = rest.indexOf(')');
        if (paren < 0) {
            return rest;
        }
        return rest.substring(paren + 1).strip();
    }

    static List<String> sentences(String content) {
        List<String> out = new ArrayList<>();
        if (content == null) {
            return out;
        }
        for (String s : content.split("(?<=[。！？!?；;])|\\n")) {
            String t = s.strip();
            if (t.length() >= 4) {
                out.add(t);
            }
        }
        return out;
    }

    private static int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 2);
    }

    /** 解析出的资料块。 */
    static final class Ref {
        final int index;
        final String content;

        Ref(int index, String content) {
            this.index = index;
            this.content = content;
        }
    }

    private static final class Scored {
        final int ref;
        final String sentence;
        final double score;

        Scored(int ref, String sentence, double score) {
            this.ref = ref;
            this.sentence = sentence;
            this.score = score;
        }
    }
}
