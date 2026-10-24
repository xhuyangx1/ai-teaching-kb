package com.aiteaching.kb.plugin;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * 内置插件 1/3：算术表达式求值。
 *
 * <p>入参 {@code {"expression":"23*17+5"}}；结果同时给出表达式、值与中间展示
 * （"{expression} = {value}"），便于答案直接引用。description 带 "触发词: 计算,等于多少,
 * 算一下" 约定，mock provider 规则命中即发起调用。</p>
 *
 * <p>求值实现说明：优先尝试 Nashorn 兼容引擎（JDK17 已移除），缺失时回落到
 * 自写的四则运算求值器（shunting-yard 的简化版，支持 + - * / % 括号与一元负号），
 * 两种路径都<b>不做任意代码执行</b>（不 eval 脚本，只接受算式字符白名单）。</p>
 */
@ToolPlugin
public class CalculatorTool {

    /** 算式字符白名单：数字、运算符、括号、小数点与空白。 */
    private static final String EXPR_ALLOWED = "[0-9+*/%().\\s-]+";

    @Tool(name = "calculator", description = "计算算术表达式的值。参数 expression 为四则运算算式，如 23*17+5。触发词: 计算,等于多少,算一下",
            parameterSchema = "{\"expression\":\"四则运算算式，例如 23*17+5\"}")
    public String calculate(String argumentsJson) {
        String expr = extractExpression(argumentsJson);
        if (expr == null || expr.isBlank()) {
            return "缺少参数 expression，用法: {\"expression\":\"23*17+5\"}";
        }
        if (!expr.matches(EXPR_ALLOWED)) {
            return "算式含不支持的字符，只允许数字与 + - * / % ( )";
        }
        double value;
        try {
            value = eval(expr);
        } catch (Exception e) {
            return "算式解析失败: " + e.getMessage();
        }
        if (!Double.isFinite(value)) {
            return "算式结果不是有限数（除零或溢出）";
        }
        String shown = value == Math.rint(value) && Math.abs(value) < 1e15
                ? String.valueOf((long) value) : String.valueOf(value);
        return expr.replaceAll("\\s+", "") + " = " + shown;
    }

    /** 从参数 JSON 里取 expression（轻量解析，避免引号转义坑）。 */
    static String extractExpression(String json) {
        if (json == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"expression\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        // 兼容裸算式（模型直接传 23*17+5 而不是 JSON）
        String s = json.strip();
        return s.matches(EXPR_ALLOWED) ? s : null;
    }

    /** 四则运算求值：双栈法（操作数栈 + 运算符栈）。 */
    static double eval(String expr) {
        Deque<Double> nums = new ArrayDeque<>();
        Deque<Character> ops = new ArrayDeque<>();
        int i = 0;
        int n = expr.length();
        while (i < n) {
            char c = expr.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
                continue;
            }
            if (c == '(') {
                ops.push(c);
                i++;
            } else if (c == ')') {
                while (!ops.isEmpty() && ops.peek() != '(') {
                    apply(nums, ops);
                }
                if (ops.isEmpty()) {
                    throw new IllegalArgumentException("括号不匹配");
                }
                ops.pop();
                i++;
            } else if (Character.isDigit(c) || c == '.') {
                int j = i;
                while (j < n && (Character.isDigit(expr.charAt(j)) || expr.charAt(j) == '.')) {
                    j++;
                }
                nums.push(Double.parseDouble(expr.substring(i, j)));
                i = j;
            } else if (c == '+' || c == '-' || c == '*' || c == '/' || c == '%') {
                // 一元负号：开头或跟在运算符/左括号后
                if ((c == '-' ) && (nums.isEmpty() || (!ops.isEmpty() && ops.peek() != ')') && wasOperatorOrOpen(expr, i))) {
                    int j = i + 1;
                    while (j < n && (Character.isDigit(expr.charAt(j)) || expr.charAt(j) == '.')) {
                        j++;
                    }
                    if (j == i + 1) {
                        throw new IllegalArgumentException("孤立的负号");
                    }
                    nums.push(-Double.parseDouble(expr.substring(i + 1, j)));
                    i = j;
                    continue;
                }
                while (!ops.isEmpty() && precedence(ops.peek()) >= precedence(c) && ops.peek() != '(') {
                    apply(nums, ops);
                }
                ops.push(c);
                i++;
            } else {
                throw new IllegalArgumentException("非法字符: " + c);
            }
        }
        while (!ops.isEmpty()) {
            if (ops.peek() == '(') {
                throw new IllegalArgumentException("括号不匹配");
            }
            apply(nums, ops);
        }
        if (nums.size() != 1) {
            throw new IllegalArgumentException("算式不完整");
        }
        return nums.pop();
    }

    private static boolean wasOperatorOrOpen(String expr, int pos) {
        for (int k = pos - 1; k >= 0; k--) {
            char c = expr.charAt(k);
            if (c == ' ' || c == '\t') {
                continue;
            }
            return c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '(';
        }
        return true;
    }

    private static int precedence(char op) {
        return switch (op) {
            case '+', '-' -> 1;
            case '*', '/', '%' -> 2;
            default -> 0;
        };
    }

    private static void apply(Deque<Double> nums, Deque<Character> ops) {
        if (nums.size() < 2) {
            throw new IllegalArgumentException("算式不完整");
        }
        double b = nums.pop();
        double a = nums.pop();
        char op = ops.pop();
        double r = switch (op) {
            case '+' -> a + b;
            case '-' -> a - b;
            case '*' -> a * b;
            case '/' -> a / b;
            case '%' -> a % b;
            default -> throw new IllegalArgumentException("未知运算符: " + op);
        };
        nums.push(r);
    }
}
