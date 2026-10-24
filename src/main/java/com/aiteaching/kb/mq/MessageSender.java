package com.aiteaching.kb.mq;

/**
 * 消息发送抽象（topic 固定 KB_TASK）。两种实现语义对齐：异步投递、消费失败重试、超限进死信。
 */
public interface MessageSender {

    String TOPIC_KB_TASK = "KB_TASK";

    void send(String topic, String jsonPayload);

    /** 实现名（memory / rocketmq），用于日志与 /api/health。 */
    String mode();

    /** 优雅关闭（应用退出时停止消费线程）。 */
    default void shutdown() {
    }
}
