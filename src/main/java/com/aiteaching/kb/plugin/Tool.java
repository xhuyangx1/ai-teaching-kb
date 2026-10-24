package com.aiteaching.kb.plugin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 插件方法注解：标在 {@code @ToolPlugin} 组件的 public 方法上，
 * 启动时由 {@link ToolRegistry} 扫描注册，新增插件类无需改动主流程。
 *
 * <p>轻量版约定：被注解方法接受 0 或 1 个 {@code String} 参数（参数 JSON，
 * 形如 {@code {"expression":"23*17+5"}}），返回 {@code String}（工具结果文本，
 * 会回传给模型自愈）。参数 schema 用简化版 JSON（参数名→说明）。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {

    /** 工具名（唯一，模型 Function Calling 用这个名字指名调用）。 */
    String name();

    /** 工具描述；mock provider 按 "触发词: a,b,c" 约定做规则触发。 */
    String description();

    /** 简化参数 schema：{"参数名":"说明"}。 */
    String parameterSchema() default "{}";
}
