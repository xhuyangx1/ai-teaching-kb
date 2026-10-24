package com.aiteaching.kb.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话记忆：key {@code conv:{conversationId}}，用 RPUSH + LTRIM 保存最近 N 轮问答，TTL 30 分钟。
 *
 * <p>Redis 不可用时记忆功能关闭（返回空历史），对话仍然可用——记忆是增强而非必需。</p>
 */
@Component
public class ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);
    private static final int TTL_SECONDS = 30 * 60;

    private final RedisClientFactory factory;

    public ConversationMemory(RedisClientFactory factory) {
        this.factory = factory;
    }

    private String key(String conversationId) {
        return "conv:" + conversationId;
    }

    /** 追加一条历史（"用户: xxx" / "助手: xxx"）。 */
    public void append(String conversationId, String line, int maxLines) {
        if (conversationId == null || line == null || !factory.isAvailable()) {
            return;
        }
        try (Jedis jedis = factory.getResource()) {
            String k = key(conversationId);
            jedis.rpush(k, line);
            jedis.ltrim(k, -Math.max(2, maxLines), -1);
            jedis.expire(k, TTL_SECONDS);
        } catch (Exception e) {
            factory.markUnavailable(e.getMessage());
            log.error("写入会话记忆失败 conversationId={}", conversationId, e);
        }
    }

    /** 读取最近若干行历史（按时间正序）。 */
    public List<String> history(String conversationId, int maxLines) {
        if (conversationId == null || !factory.isAvailable()) {
            return Collections.emptyList();
        }
        try (Jedis jedis = factory.getResource()) {
            List<String> all = jedis.lrange(key(conversationId), 0, -1);
            if (all == null || all.isEmpty()) {
                return Collections.emptyList();
            }
            int from = Math.max(0, all.size() - Math.max(2, maxLines));
            return new ArrayList<>(all.subList(from, all.size()));
        } catch (Exception e) {
            factory.markUnavailable(e.getMessage());
            log.error("读取会话记忆失败 conversationId={}", conversationId, e);
            return Collections.emptyList();
        }
    }
}
