package com.aiteaching.kb.plugin;

import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 插件组件标记：组合了 {@code @Component}，标注在插件类上。
 *
 * <p>ToolRegistry 启动时通过 {@code getBeansWithAnnotation(ToolPlugin.class)}
 * 收集全部插件 Bean —— 因此<b>新增插件只需新建一个标注了本注解的类</b>，
 * 主流程（ChatService / 消费者）零改动；这也回答了"为什么不用 SPI"
 * （追问链 #60：Spring 容器本身已经是注册表，SPI 是多余的间接层）。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface ToolPlugin {
}
