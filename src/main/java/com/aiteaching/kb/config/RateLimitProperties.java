package com.aiteaching.kb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 限流配置（{@code kb.rate-limit.*}），支持<b>用户与 IP 双维度</b>各自的桶参数。
 *
 * <p>为什么两个维度都要：只按 userId 限流时，攻击者换一个 userId 就能绕过；
 * 只按 IP 限流时，同一个出口 IP（公司 NAT、校园网）的正常用户会互相挤占配额。
 * 双维度同时生效，任一维度超限即 429。</p>
 */
@Component
@ConfigurationProperties(prefix = "kb.rate-limit")
public class RateLimitProperties {

    // ---------- 用户维度 ----------
    private boolean userEnabled = true;
    private int qps = 5;
    private int capacity = 10;

    // ---------- IP 维度 ----------
    private boolean ipEnabled = true;
    /** IP 维度通常比单用户宽松：同一出口 IP 后面可能有很多人。 */
    private int ipQps = 50;
    private int ipCapacity = 100;

    /**
     * 是否信任 X-Forwarded-For / X-Real-IP 头。
     *
     * <p><b>默认 false（安全优先）</b>：这两个头由客户端提供，直连或不可信网络下可被伪造——
     * 攻击者只需每次换一个 XFF 值就能为每个请求拿到一个新令牌桶，从而完全绕过 IP 维度限流。
     * 因此默认只认 TCP 层的 {@code remoteAddr}。</p>
     *
     * <p>仅当应用确实部署在<b>可信反向代理</b>（Nginx/SLB 等，且代理会重写该头）之后时，
     * 才应把 {@code kb.rate-limit.trust-forwarded-for} 置为 true，此时取 XFF 首段作为客户端 IP。</p>
     */
    private boolean trustForwardedFor = false;

    public boolean isUserEnabled() {
        return userEnabled;
    }

    public void setUserEnabled(boolean userEnabled) {
        this.userEnabled = userEnabled;
    }

    public int getQps() {
        return qps;
    }

    public void setQps(int qps) {
        this.qps = qps;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public boolean isIpEnabled() {
        return ipEnabled;
    }

    public void setIpEnabled(boolean ipEnabled) {
        this.ipEnabled = ipEnabled;
    }

    public int getIpQps() {
        return ipQps;
    }

    public void setIpQps(int ipQps) {
        this.ipQps = ipQps;
    }

    public int getIpCapacity() {
        return ipCapacity;
    }

    public void setIpCapacity(int ipCapacity) {
        this.ipCapacity = ipCapacity;
    }

    public boolean isTrustForwardedFor() {
        return trustForwardedFor;
    }

    public void setTrustForwardedFor(boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }
}
