package com.aiteaching.kb.config;

import com.aiteaching.kb.redis.RedisClientFactory;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 相关 Bean：连接池工厂。启动时探测一次连通性，失败只打日志不阻断启动（离线可跑）。
 */
@Configuration
public class RedisConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    private RedisClientFactory factory;

    @Bean
    public RedisClientFactory redisClientFactory(KbProperties props) {
        this.factory = new RedisClientFactory(props);
        boolean ok = factory.ping();
        log.info("Redis 状态: {}", ok ? "可用（限流/会话记忆启用）" : "不可用（限流放行 + 会话记忆关闭，诚实降级）");
        return factory;
    }

    @PreDestroy
    public void destroy() {
        if (factory != null) {
            factory.close();
        }
    }
}
