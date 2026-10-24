package com.aiteaching.kb.redis;

import com.aiteaching.kb.config.RateLimitProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 解析调用方 IP，供 IP 维度限流使用。
 *
 * <p>放在 {@code redis} 包内（与限流同模块）并自行从 {@code RequestContextHolder} 取当前请求，
 * 这样限流所需的信息不需要穿过 Controller/Service 的方法签名逐层传递 —— 减少调用方与限流的耦合。</p>
 *
 * <p><b>X-Forwarded-For 的取舍</b>：该头可被客户端伪造，一旦信任就等于把限流维度交给攻击者
 * （每次换一个 XFF 值即可拿到新桶，IP 限流形同虚设）。因此
 * {@code kb.rate-limit.trust-forwarded-for} <b>默认 false</b>：只认 TCP 层的 {@code remoteAddr}。
 * 仅当应用部署在会重写该头的<b>可信反向代理</b>之后时才应开启，此时取 XFF 首段（最靠近客户端的
 * 一跳）作为客户端 IP。多级代理下该首段仍由客户端提供，需由代理负责清洗。</p>
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    private final RateLimitProperties props;

    public ClientIpResolver(RateLimitProperties props) {
        this.props = props;
    }

    /**
     * 解析当前请求的客户端 IP。
     *
     * @return IP；无 Web 上下文（例如消费线程内调用）时返回 null，调用方应跳过 IP 维度限流
     */
    public String resolve() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        if (props.isTrustForwardedFor()) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                // XFF 是逗号分隔链：第一段是最初的客户端
                int comma = xff.indexOf(',');
                String first = (comma > 0 ? xff.substring(0, comma) : xff).trim();
                if (!first.isEmpty()) {
                    return normalize(first);
                }
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return normalize(realIp.trim());
            }
        }
        return normalize(request.getRemoteAddr());
    }

    private HttpServletRequest currentRequest() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                return attrs.getRequest();
            }
        } catch (Exception e) {
            // 非 Web 线程（如 MQ 消费线程）没有请求上下文，属正常情况
            log.debug("当前线程无 Web 请求上下文，跳过 IP 解析: {}", e.getMessage());
        }
        return null;
    }

    /** IPv6 的 loopback 表示统一成 IPv4 写法，避免同一台机器产生两个限流桶。 */
    private static String normalize(String ip) {
        if (ip == null) {
            return null;
        }
        String v = ip.trim();
        if ("0:0:0:0:0:0:0:1".equals(v) || "::1".equals(v)) {
            return "127.0.0.1";
        }
        return v.isEmpty() ? null : v;
    }
}
