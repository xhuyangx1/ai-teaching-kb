package com.aiteaching.kb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MQ 相关配置（{@code kb.mq.*}）。
 *
 * <p>{@code config.KbProperties} 保留 {@code kb.mq.mode} 用于 Bean 装配，本类承载重试/退避/
 * 队列等运行时参数——两者绑定同一 {@code kb.mq} 前缀的不同字段，Spring 默认忽略未知字段，
 * 因此可以并存。</p>
 */
@Component
@ConfigurationProperties(prefix = "kb.mq")
public class MqProperties {

    /**
     * 真实模式下的 topic。默认与 {@link MessageSender#TOPIC_KB_TASK} 一致。
     * 对外开关是 {@code app.mq.enabled}（由 {@link MqEnabledBridge} 归一化到 {@code kb.mq.mode}），
     * 不在这里重复定义布尔位，避免两个键语义重叠。
     */
    private String topic = "KB_TASK";

    private final Retry retry = new Retry();
    private final Memory memory = new Memory();

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public Retry getRetry() {
        return retry;
    }

    public Memory getMemory() {
        return memory;
    }

    /** 消费失败重试策略（内存与 RocketMQ 两种模式共用同一组语义参数）。 */
    public static class Retry {
        /** 最大投递尝试次数（含首次）。3 表示首次 + 2 次重试。 */
        private int maxAttempts = 3;
        /** 指数退避基数（毫秒）：200 → 200/400/800。 */
        private long backoffMs = 200L;
        /** 退避上限，避免配置放大导致长时间挂起。 */
        private long maxBackoffMs = 5_000L;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getBackoffMs() {
            return backoffMs;
        }

        public void setBackoffMs(long backoffMs) {
            this.backoffMs = backoffMs;
        }

        public long getMaxBackoffMs() {
            return maxBackoffMs;
        }

        public void setMaxBackoffMs(long maxBackoffMs) {
            this.maxBackoffMs = maxBackoffMs;
        }
    }

    /** 内存队列参数。 */
    public static class Memory {
        /** 每 topic 队列容量；满则拒绝入队（返回 false 并标记任务失败，不静默丢弃）。 */
        private int queueCapacity = 10_000;
        /** 消费线程数。 */
        private int workers = 4;
        /** 死信保留条数（内存中最多保留，供 /api/health 观测）。 */
        private int deadLetterRetained = 100;

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getWorkers() {
            return workers;
        }

        public void setWorkers(int workers) {
            this.workers = workers;
        }

        public int getDeadLetterRetained() {
            return deadLetterRetained;
        }

        public void setDeadLetterRetained(int deadLetterRetained) {
            this.deadLetterRetained = deadLetterRetained;
        }
    }
}
