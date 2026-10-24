package com.aiteaching.kb.config;

import com.aiteaching.kb.mq.ImportTaskConsumer;
import com.aiteaching.kb.mq.InMemoryMessageSender;
import com.aiteaching.kb.mq.MessageSender;
import com.aiteaching.kb.mq.RocketMqMessageSender;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息实现装配：{@code kb.mq.mode=memory|rocketmq}。
 *
 * <p>两个实现类始终存在于产物中（对应验收清单"源码存在两个分支实现"），
 * 由配置决定装配哪一个。当前装配结果可从 {@code GET /api/health} 的 mqMode 字段看到。</p>
 */
@Configuration
public class MqConfig {

    private static final Logger log = LoggerFactory.getLogger(MqConfig.class);

    private MessageSender sender;

    @Bean
    @ConditionalOnProperty(name = "kb.mq.mode", havingValue = "memory", matchIfMissing = true)
    public MessageSender inMemoryMessageSender(ImportTaskConsumer consumer) {
        log.info("消息模式: memory（内存队列，语义对齐真 MQ：异步投递/失败重试/超限死信）");
        InMemoryMessageSender s = new InMemoryMessageSender(consumer);
        this.sender = s;
        return s;
    }

    @Bean
    @ConditionalOnProperty(name = "kb.mq.mode", havingValue = "rocketmq")
    public MessageSender rocketMqMessageSender(KbProperties props, ImportTaskConsumer consumer) {
        log.info("消息模式: rocketmq（namesrv={}）", props.getMq().getRocketmq().getNamesrvAddr());
        RocketMqMessageSender s = new RocketMqMessageSender(props, consumer);
        this.sender = s;
        return s;
    }

    @PreDestroy
    public void destroy() {
        if (sender != null) {
            try {
                sender.shutdown();
            } catch (Exception e) {
                log.debug("关闭消息发送器异常: {}", e.getMessage());
            }
        }
    }
}
