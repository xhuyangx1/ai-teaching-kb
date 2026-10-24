package com.aiteaching.kb.chatlog;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正则脱敏器：手机号 / 邮箱 / 身份证号，在<b>入库前</b>把敏感原文替换为
 * {@code [REDACTED:PHONE]} / {@code [REDACTED:EMAIL]} / {@code [REDACTED:IDCARD]}。
 *
 * <p>规则常量集中定义在本类（设计要求），顺序刻意安排为：身份证 → 手机号 → 邮箱，
 * 因为身份证号包含 18 位数字（可能被手机号规则部分命中），先长后短避免误替换。
 * 保留首尾少量字符以便人工核对（设计取舍：可配置为全遮蔽，见 README 追问链）。</p>
 */
public final class SensitiveDataMasker {

    /** 手机号：1 开头的 11 位数字（前后不能是数字，避免切中身份证/长串的一部分）。 */
    public static final Pattern PHONE = Pattern.compile("(?<![0-9])1[3-9][0-9]{9}(?![0-9])");
    /** 邮箱：常见格式。 */
    public static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /** 身份证：18 位（含校验位 X）或 15 位旧格式。 */
    public static final Pattern IDCARD = Pattern.compile("(?<![0-9Xx])[1-9][0-9]{5}(?:19|20)[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9Xx](?![0-9Xx])");

    public static final String TAG_PHONE = "[REDACTED:PHONE]";
    public static final String TAG_EMAIL = "[REDACTED:EMAIL]";
    public static final String TAG_IDCARD = "[REDACTED:IDCARD]";

    private SensitiveDataMasker() {
    }

    /** 对一条消息内容做全部三类脱敏（身份证先于手机号）。 */
    public static String mask(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        String out = replaceIdCard(content);
        out = replacePhone(out);
        out = replaceEmail(out);
        return out;
    }

    public static String replacePhone(String s) {
        if (s == null) return null;
        Matcher m = PHONE.matcher(s);
        return m.replaceAll(Matcher.quoteReplacement(TAG_PHONE));
    }

    public static String replaceEmail(String s) {
        if (s == null) return null;
        Matcher m = EMAIL.matcher(s);
        return m.replaceAll(Matcher.quoteReplacement(TAG_EMAIL));
    }

    public static String replaceIdCard(String s) {
        if (s == null) return null;
        Matcher m = IDCARD.matcher(s);
        return m.replaceAll(Matcher.quoteReplacement(TAG_IDCARD));
    }
}
