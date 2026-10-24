package com.aiteaching.kb.plugin;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置插件 2/3：当前时间查询与日期差计算。
 *
 * <p>两种用法：</p>
 * <ul>
 *   <li>{@code {}}（无参数）→ 返回当前日期时间与星期；</li>
 *   <li>{@code {"from":"2026-10-01","to":"2026-12-31"}} → 返回相差天数。</li>
 * </ul>
 */
@ToolPlugin
public class DateTimeTool {

    private static final Pattern DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");

    @Tool(name = "datetime", description = "查询当前日期时间，或计算两个日期相差天数（参数 from/to，格式 yyyy-MM-dd）。触发词: 现在几点,今天几号,日期,相差多少天",
            parameterSchema = "{\"from\":\"起始日期 yyyy-MM-dd（可选）\",\"to\":\"结束日期 yyyy-MM-dd（可选）\"}")
    public String dateTime(String argumentsJson) {
        String from = extractField(argumentsJson, "from");
        String to = extractField(argumentsJson, "to");
        if (from != null && to != null) {
            LocalDate d1 = parseDate(from);
            LocalDate d2 = parseDate(to);
            if (d1 == null || d2 == null) {
                return "日期格式应为 yyyy-MM-dd，例如 {\"from\":\"2026-10-01\",\"to\":\"2026-12-31\"}";
            }
            long days = ChronoUnit.DAYS.between(d1, d2);
            return d1 + " 到 " + d2 + " 相差 " + Math.abs(days) + " 天";
        }
        LocalDateTime now = LocalDateTime.now();
        String weekday = now.getDayOfWeek().name();
        return "当前时间: " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + "（" + chineseWeekday(weekday) + "）";
    }

    private static String extractField(String json, String field) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    private static LocalDate parseDate(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = DATE.matcher(s);
        if (!m.find()) {
            try {
                return LocalDate.parse(s.strip(), DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            } catch (DateTimeParseException e) {
                return null;
            }
        }
        return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    private static String chineseWeekday(String iso) {
        return switch (iso) {
            case "MONDAY" -> "星期一";
            case "TUESDAY" -> "星期二";
            case "WEDNESDAY" -> "星期三";
            case "THURSDAY" -> "星期四";
            case "FRIDAY" -> "星期五";
            case "SATURDAY" -> "星期六";
            case "SUNDAY" -> "星期日";
            default -> iso;
        };
    }
}
