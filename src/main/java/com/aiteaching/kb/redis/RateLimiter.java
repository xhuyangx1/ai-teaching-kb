package com.aiteaching.kb.redis;

import com.aiteaching.kb.common.BusinessException;
import com.aiteaching.kb.config.RateLimitProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;

import java.util.List;

/**
 * 令牌桶限流（Redis + Lua，兼容 Redis 3.0：只用 EVAL / HMGET / HMSET / PEXPIRE）。
 *
 * <p><b>双维度</b>：按 {@code userId} 与 {@code ip} 各判一次桶，任一超限即 429。
 * 只按用户限流会被"换 userId"绕过；只按 IP 限流会让同一出口 IP 的正常用户互相挤占配额。</p>
 *
 * <p><b>为什么用 Lua</b>：判定必须"补充令牌 → 比较 → 扣减"三步原子完成。若在客户端做
 * GET 再 SET，两个并发请求会读到同一个旧令牌数，双双放行（超发）。Lua 在 Redis 单线程内执行，
 * 天然互斥（对应追问链"为什么不用先 GET 再 SET"）。</p>
 *
 * <p><b>诚实降级</b>：Redis 连不上或脚本报错时<b>放行</b>并打 WARN，
 * 绝不让限流组件把服务拖挂（限流是保护措施，不是业务正确性的必要条件）。</p>
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /**
     * 令牌桶脚本。
     * KEYS[1]=桶 key；ARGV=[qps, capacity, nowMillis, requested]；返回 1 放行 / 0 拒绝。
     *
     * <p>早期 Redis（3.0）没有 HMGET 返回 nil 元素的问题，但要注意 tonumber(nil) 的判定，
     * 这里用 {@code data[1] == false} 显式处理首次访问，避免把"桶不存在"误判成 0 令牌。</p>
     */
    private static final String LUA = """
            local key = KEYS[1]
            local qps = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then
              tokens = capacity
              ts = now
            end
            local delta = math.max(0, now - ts) / 1000.0
            tokens = math.min(capacity, tokens + delta * qps)
            local allowed = 0
            if tokens >= requested then
              tokens = tokens - requested
              allowed = 1
            end
            redis.call('HMSET', key, 'tokens', tokens, 'ts', now)
            local ttl = math.ceil(capacity / qps * 1000) + 1000
            if ttl < 1000 then
              ttl = 1000
            end
            redis.call('PEXPIRE', key, ttl)
            return allowed
            """;

    private final RedisClientFactory factory;
    private final RateLimitProperties props;
    private final ClientIpResolver ipResolver;

    public RateLimiter(RedisClientFactory factory, RateLimitProperties props, ClientIpResolver ipResolver) {
        this.factory = factory;
        this.props = props;
        this.ipResolver = ipResolver;
    }

    /**
     * 字符串维度：仅按 userId 限流（保留旧签名，兼容既有调用方与单测）。
     *
     * @throws BusinessException 429 桶内无令牌
     */
    public void acquire(String userId) {
        acquire(userId, null);
    }

    /**
     * 双维度限流：先判用户桶，再判 IP 桶；任一拒绝即抛 429。
     *
     * @param userId 用户标识，可为空（空则跳过用户维度）
     * @param ip     客户端 IP，可为空（空则跳过 IP 维度）
     * @throws BusinessException 429 桶内无令牌
     */
    public void acquire(String userId, String ip) {
        // 调用方没显式给 IP 时，自行从当前 Web 请求解析（无 Web 上下文则为 null）
        String effectiveIp = (ip == null || ip.isBlank()) && props.isIpEnabled() ? ipResolver.resolve() : ip;
        if (props.isUserEnabled() && userId != null && !userId.isBlank()) {
            if (!tryAcquire("rl:user:" + userId, props.getQps(), props.getCapacity())) {
                throw new BusinessException(429, "请求过于频繁，请稍后重试（用户维度限流 qps="
                        + props.getQps() + ", capacity=" + props.getCapacity() + "）");
            }
        }
        if (props.isIpEnabled() && effectiveIp != null && !effectiveIp.isBlank()) {
            if (!tryAcquire("rl:ip:" + effectiveIp, props.getIpQps(), props.getIpCapacity())) {
                throw new BusinessException(429, "请求过于频繁，请稍后重试（IP 维度限流 qps="
                        + props.getIpQps() + ", capacity=" + props.getIpCapacity() + "）");
            }
        }
    }

    /**
     * 尝试取一个令牌。
     *
     * @return true 放行；false 明确被限流；Redis 故障时也返回 true（降级放行）
     */
    private boolean tryAcquire(String key, int qps, int capacity) {
        if (!factory.isAvailable()) {
            log.warn("Redis 不可用，限流降级放行 key={}（服务保持可用，不因限流组件失败而拒绝请求）", key);
            return true;
        }
        int safeQps = Math.max(1, qps);
        int safeCapacity = Math.max(1, capacity);
        try (Jedis jedis = factory.getResource()) {
            Object result = jedis.eval(LUA, List.of(key),
                    List.of(String.valueOf(safeQps), String.valueOf(safeCapacity),
                            String.valueOf(System.currentTimeMillis()), "1"));
            long allowed = result instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(result));
            if (allowed != 1L) {
                log.debug("限流命中 key={} qps={} capacity={}", key, safeQps, safeCapacity);
                return false;
            }
            return true;
        } catch (Exception e) {
            // 连接断了 / 脚本报错 / 老版本不支持某些命令：一律降级放行
            factory.markUnavailable(e.getMessage());
            log.warn("限流 Lua 执行失败，降级放行 key={}：{}（后续请求将直接走降级分支）", key, e.getMessage());
            return true;
        }
    }

    /** 供 /api/health 展示当前限流实况。 */
    public String status() {
        if (!factory.isAvailable()) {
            return "degraded(pass-through, redis unavailable)";
        }
        StringBuilder sb = new StringBuilder("redis-lua-token-bucket(");
        if (props.isUserEnabled()) {
            sb.append("user:qps=").append(props.getQps()).append(",capacity=").append(props.getCapacity());
        }
        if (props.isIpEnabled()) {
            if (props.isUserEnabled()) {
                sb.append(";");
            }
            sb.append("ip:qps=").append(props.getIpQps()).append(",capacity=").append(props.getIpCapacity());
        }
        return sb.append(")").toString();
    }
}
