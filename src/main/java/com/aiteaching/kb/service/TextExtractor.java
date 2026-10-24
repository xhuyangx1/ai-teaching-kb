package com.aiteaching.kb.service;

import com.aiteaching.kb.common.BusinessException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 文本抽取：MD/TXT 直接按 UTF-8 解码，DOCX 用 Apache POI 逐段读取。
 *
 * <p>编码兜底：UTF-8 解码出现替换字符（U+FFFD）时改用 GBK 重试一次——
 * 中文环境常见的"TXT 是 GBK"场景（防乱码验收点）。</p>
 */
@Component
public class TextExtractor {

    private static final Pattern MD_HEADING = Pattern.compile("^#{1,6}\\s.*$", Pattern.MULTILINE);

    /** 支持的文档扩展名。 */
    public static String detectType(String filename) {
        if (filename == null) {
            throw new BusinessException(400, "文件名不能为空");
        }
        String lower = filename.toLowerCase();
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "MD";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".text") || lower.endsWith(".log")) {
            return "TXT";
        }
        if (lower.endsWith(".docx")) {
            return "DOCX";
        }
        throw new BusinessException(400, "不支持的文件类型（仅支持 .md / .txt / .docx）: " + filename);
    }

    public String extract(String docType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(400, "文件内容为空");
        }
        return switch (docType == null ? "" : docType.toUpperCase()) {
            case "MD", "MARKDOWN" -> decode(bytes);
            case "TXT", "TEXT", "LOG" -> decode(bytes);
            case "DOCX" -> extractDocx(bytes);
            default -> throw new BusinessException(400, "不支持的文档类型: " + docType);
        };
    }

    private String decode(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('\uFFFD') >= 0) {
            String gbk = new String(bytes, java.nio.charset.Charset.forName("GBK"));
            if (gbk.indexOf('\uFFFD') < 0) {
                return stripBom(gbk);
            }
        }
        return stripBom(utf8);
    }

    private static String stripBom(String s) {
        return s != null && s.startsWith("\uFEFF") ? s.substring(1) : s;
    }

    private String extractDocx(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = p.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text.strip()).append('\n');
                }
            }
            // 表格内容同样纳入（教学文档常把知识点放表格里）
            doc.getTables().forEach(t -> t.getRows().forEach(row -> {
                StringBuilder line = new StringBuilder();
                row.getTableCells().forEach(c -> line.append(c.getText().strip()).append(" | "));
                if (line.length() > 0) {
                    sb.append(line.toString().strip()).append('\n');
                }
            }));
        } catch (Exception e) {
            throw new BusinessException(400, "DOCX 解析失败: " + e.getMessage(), e);
        }
        return stripBom(sb.toString()).strip();
    }

    /** 去掉 Markdown 标题标记，保留正文（切分与检索都更干净）。 */
    public String normalizeMarkdown(String text) {
        if (text == null) {
            return "";
        }
        return MD_HEADING.matcher(text).replaceAll(m -> m.group().trim());
    }
}
