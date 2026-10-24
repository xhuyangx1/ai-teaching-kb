package com.aiteaching.kb.mq;

import com.aiteaching.kb.config.KbProperties;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 真 RocketMQ 实现（rocketmq-client 5.3.0），{@code kb.mq.mode=rocketmq} 时装配。
 *
 * <p>与内存实现语义对齐：同 topic（KB_TASK）、同消息体、同幂等消费入口
 * （{@link ImportTaskConsumer} 里的状态机 CAS 对两种模式共用）。</p>
 *
 * <p><b>诚实说明</b>：本机 RocketMQ 9876 未监听且安装目录不可写，这条链路<b>未经端到端验证</b>，
 * 只保证代码分支可编译、可装配、可被配置开关选中。启动连接失败时会打 ERROR 并保持应用可用
 * （任务将停留在 PENDING，可通过 /api/tasks/{taskId} 观察到失败）。</p>
 */
public class RocketMqMessageSender implements MessageSender {

    private static final Logger log = LoggerFactory.getLogger(RocketMqMessageSender.class);

    private final KbProperties props;
    private final ImportTaskConsumer consumer;
    private DefaultMQProducer producer;
    private DefaultMQPushConsumer pushConsumer;
    private volatile boolean started = false;

    public RocketMqMessageSender(KbProperties props, ImportTaskConsumer consumer) {
        this.props = props;
        this.consumer = consumer;
    }

    @Override
    public void send(String topic, String jsonPayload) {
        String t = topic == null ? TOPIC_KB_TASK : topic;
        if (!ensureStarted()) {
            throw new IllegalStateException("RocketMQ 不可用（namesrv=" + props.getMq().getRocketmq().getNamesrvAddr() + "），消息未投递");
        }
        try {
            Message message = new Message(t, jsonPayload.getBytes(StandardCharsets.UTF_8));
            producer.send(message);
            log.debug("RocketMQ 消息已发送 topic={}", t);
        } catch (Exception e) {
            throw new IllegalStateException("RocketMQ 发送失败: " + e.getMessage(), e);
        }
    }

    private synchronized boolean ensureStarted() {
        if (started) {
            return true;
        }
        KbProperties.RocketMq cfg = props.getMq().getRocketmq();
        try {
            producer = new DefaultMQProducer(cfg.getConsumerGroup() + "_producer");
            producer.setNamesrvAddr(cfg.getNamesrvAddr());
            producer.setSendMsgTimeout(3000);
            producer.start();

            pushConsumer = new DefaultMQPushConsumer(cfg.getConsumerGroup());
            pushConsumer.setNamesrvAddr(cfg.getNamesrvAddr());
            pushConsumer.subscribe(cfg.getTopic(), "*");
            pushConsumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
                for (MessageExt msg : msgs) {
                    String payload = new String(msg.getBody(), StandardCharsets.UTF_8);
                    try {
                        boolean ok = consumer.consume(payload);
                        if (!ok) {
                            // 手动 ACK 语义：返回 RECONSUME_LATER 交给 RocketMQ 重投
                            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                        }
                    } catch (Exception e) {
                        log.error("RocketMQ 消费异常 msgId={}", msg.getMsgId(), e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });
            pushConsumer.start();
            started = true;
            log.info("RocketMQ 已启动 namesrv={} topic={} group={}",
                    cfg.getNamesrvAddr(), cfg.getTopic(), cfg.getConsumerGroup());
            return true;
        } catch (Exception e) {
            log.error("RocketMQ 启动失败（namesrv={}），该模式不可用；请检查 broker 是否监听 9876",
                    cfg.getNamesrvAddr(), e);
            shutdownQuietly();
            return false;
        }
    }

    @Override
    public String mode() {
        return "rocketmq";
    }

    public boolean isStarted() {
        return started;
    }

    @Override
    @PreDestroy
    public void shutdown() {
        shutdownQuietly();
    }

    private void shutdownQuietly() {
        started = false;
        try {
            if (pushConsumer != null) {
                pushConsumer.shutdown();
            }
        } catch (Exception e) {
            log.debug("关闭 RocketMQ 消费者异常: {}", e.getMessage());
        }
        try {
            if (producer != null) {
                producer.shutdown();
            }
        } catch (Exception e) {
            log.debug("关闭 RocketMQ 生产者异常: {}", e.getMessage());
        }
    }
}
