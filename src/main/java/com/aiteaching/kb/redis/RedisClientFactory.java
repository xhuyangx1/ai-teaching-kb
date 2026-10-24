package com.aiteaching.kb.redis;

import com.aiteaching.kb.config.KbProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

/**
 * Jedis 直连工厂（jedis 5.0.2）。
 *
 * <p>用途仅限 KV：令牌桶限流 + 会话记忆。本机 Redis 3.0.504 不支持 RediSearch，
 * 因此向量检索走内存扁平库，不依赖 Redis 模块。</p>
 * <p><b>诚实降级</b>：Redis 不可用时 {@link #isAvailable()} 返回 false，调用方放行并打 ERROR 日志，
 * 不让限流组件成为单点故障。</p>
 */
public class RedisClientFactory {

    private static final Logger log = LoggerFactory.getLogger(RedisClientFactory.class);

    private final JedisPool pool;
    private volatile boolean available = true;

    public RedisClientFactory(KbProperties props) {
        KbProperties.Redis r = props.getRedis();
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        config.setMaxIdle(8);
        config.setMinIdle(0);
        config.setTestOnBorrow(false);
        this.pool = new JedisPool(config, r.getHost(), r.getPort(), r.getTimeoutMs());
        log.info("Redis 连接池已初始化 host={} port={}", r.getHost(), r.getPort());
    }

    /** 借用连接（调用方负责 close，try-with-resources）。 */
    public Jedis getResource() {
        return pool.getResource();
    }

    public boolean isAvailable() {
        return available;
    }

    /** 标记不可用（例如执行 Lua 失败），后续请求直接走降级分支。 */
    public void markUnavailable(String reason) {
        if (available) {
            available = false;
            log.error("Redis 不可用，限流/记忆降级: {}", reason);
        }
    }

    public void markAvailable() {
        available = true;
    }

    public void close() {
        try {
            pool.close();
        } catch (Exception e) {
            log.warn("关闭 Redis 连接池异常: {}", e.getMessage());
        }
    }

    /** 仅用于探测连通性（启动时调用一次，失败不阻断启动）。 */
    public boolean ping() {
        try (Jedis jedis = getResource()) {
            String pong = jedis.ping();
            available = "PONG".equalsIgnoreCase(pong);
            if (!available) {
                log.error("Redis PING 返回异常: {}", pong);
            }
            return available;
        } catch (Exception e) {
            available = false;
            log.error("Redis 连接失败，限流与会话记忆将降级（诚实兜底）: {}", e.getMessage());
            return false;
        }
    }

    public Duration timeout() {
        return Duration.ofMillis(2000);
    }
}
