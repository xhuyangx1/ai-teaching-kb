package com.aiteaching.kb.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把对外开关 {@code app.mq.enabled} 映射到内部选择器 {@code kb.mq.mode}。
 *
 * <p><b>为什么需要这层桥接</b>：任务书 §5 已把 {@code kb.mq.mode=memory|rocketmq} 定义为冻结契约，
 * 而验收要求同时支持 {@code app.mq.enabled=true|false} 这个布尔开关。与其把两个键都做成 Bean 条件
 * （容易出现两份 {@code MessageSender} Bean 的类型歧义），不如在环境准备阶段做一次显式归一化：
 * 只保留一个装配点（{@code MqConfig} 读 {@code kb.mq.mode}），两个键都能用且语义清晰。</p>
 *
 * <p>优先级：{@code app.mq.enabled} 显式出现时才覆盖 {@code kb.mq.mode}；
 * 未配置时完全不影响 {@code kb.mq.mode} 的默认行为（向后兼容）。</p>
 */
public class MqEnabledBridge implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(MqEnabledBridge.class);

    /** 对外开关。 */
    public static final String ENABLED_KEY = "app.mq.enabled";
    /** 内部选择器（冻结契约）。 */
    public static final String MODE_KEY = "kb.mq.mode";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String enabled = environment.getProperty(ENABLED_KEY);
        if (enabled == null || enabled.isBlank()) {
            return;
        }
        String normalized = enabled.trim().toLowerCase();
        boolean on = "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
        String mode = on ? "rocketmq" : "memory";

        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put(MODE_KEY, mode);
        // 放在最前面：显式开关的推导结果优先于 application.yml 中的 kb.mq.mode
        environment.getPropertySources().addFirst(new MapPropertySource("mqEnabledBridge", derived));
        log.info("app.mq.enabled={} → kb.mq.mode={}（{}）", normalized, mode,
                on ? "真实 RocketMQ" : "内存队列，保持 MQ 语义");
    }

    @Override
    public int getOrder() {
        // 必须在 ConfigDataEnvironmentPostProcessor（加载 application.yml）之后执行，
        // 否则读不到写在 yml 里的 app.mq.enabled。
        return Ordered.LOWEST_PRECEDENCE;
    }
}
