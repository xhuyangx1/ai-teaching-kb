package com.aiteaching.kb.rag.splitter;

import com.aiteaching.kb.config.KbProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文档切分器：按段落聚合，目标 chunk-size 由配置控制，相邻 chunk 保留 overlap 字符重叠。
 *
 * <p>策略：先按空行切段 → 段落聚合到接近 chunk-size → 段落本身超长时按 chunk-size 硬切。
 * 重叠取上一块尾部 overlap 个字符，保证跨块语义不被切断（追问链"为什么不用固定字数切"）。
 * </p>
 */
@Component
public class DocSplitter {

    private final KbProperties props;

    public DocSplitter(KbProperties props) {
        this.props = props;
    }

    public List<SplitChunk> split(String rawText) {
        return split(rawText, props.getRag().getChunkSize(), props.getRag().getChunkOverlap());
    }

    public List<SplitChunk> split(String rawText, int chunkSize, int overlap) {
        List<SplitChunk> out = new ArrayList<>();
        if (rawText == null || rawText.isBlank()) {
            return out;
        }
        int size = Math.max(50, chunkSize);
        int ov = Math.max(0, Math.min(overlap, size / 2));

        String text = rawText.replace("\r\n", "\n").replace('\r', '\n');
        String[] paragraphs = text.split("\n\\s*\n");

        StringBuilder buf = new StringBuilder();
        for (String p : paragraphs) {
            String para = p.strip();
            if (para.isEmpty()) {
                continue;
            }
            if (para.length() > size) {
                if (buf.length() > 0) {
                    out.add(new SplitChunk(buf.toString()));
                    buf.setLength(0);
                }
                for (String piece : hardSplit(para, size, ov)) {
                    out.add(new SplitChunk(piece));
                }
                continue;
            }
            if (buf.length() > 0 && buf.length() + para.length() + 1 > size) {
                String prev = buf.toString();
                out.add(new SplitChunk(prev));
                buf.setLength(0);
                String tail = tail(prev, ov);
                if (!tail.isBlank()) {
                    buf.append(tail).append('\n');
                }
            }
            if (buf.length() > 0) {
                buf.append('\n');
            }
            buf.append(para);
        }
        if (buf.length() > 0) {
            out.add(new SplitChunk(buf.toString()));
        }
        return out;
    }

    private List<String> hardSplit(String para, int size, int overlap) {
        List<String> pieces = new ArrayList<>();
        int step = Math.max(1, size - overlap);
        int from = 0;
        while (from < para.length()) {
            int to = Math.min(para.length(), from + size);
            pieces.add(para.substring(from, to));
            if (to == para.length()) {
                break;
            }
            from += step;
        }
        return pieces;
    }

    private String tail(String s, int n) {
        if (n <= 0 || s.isEmpty()) {
            return "";
        }
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
