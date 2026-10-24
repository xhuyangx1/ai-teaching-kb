package com.aiteaching.kb.config;

import com.aiteaching.kb.mq.InMemoryMessageSender;
import com.aiteaching.kb.mq.MessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 把 {@link MqProperties} 注入到由外部装配的 {@link MessageSender} Bean 上。
 *
 * <p><b>为什么需要它</b>：{@code MessageSender} 的实现类由 {@link MqConfig} 以 {@code new} 的
 * 方式创建，而配置项（重试次数、退避基数、队列容量、worker 数）由 {@link MqProperties} 绑定。
 * 这里用 {@link SmartInitializingSingleton} 在所有单例就绪后做一次显式注入，
 * 避免让 Bean 工厂方法承担配置组装职责。</p>
 *
 * <p>时机安全：本方法在容器启动完成、开始接收请求之前执行，因此不会出现"用旧配置处理了请求"
 * 的情况。若实现类不支持动态配置（例如真实 RocketMQ 实现），静默跳过。</p>
 */
@Component
public class MqWiringConfigurer implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(MqWiringConfigurer.class);

    private final ObjectProvider<MessageSender> senderProvider;
    private final MqProperties props;

    public MqWiringConfigurer(ObjectProvider<MessageSender> senderProvider, MqProperties props) {
        this.senderProvider = senderProvider;
        this.props = props;
    }

    @Override
    public void afterSingletonsInstantiated() {
        MessageSender sender = senderProvider.getIfAvailable();
        if (sender == null) {
            log.warn("未发现 MessageSender Bean，MQ 配置未应用");
            return;
        }
        if (sender instanceof InMemoryMessageSender inMemory) {
            inMemory.configure(props);
            log.info("内存队列配置已应用 mode={} retryMaxAttempts={} backoffMs={} workers={} queueCapacity={}",
                    inMemory.mode(), props.getRetry().getMaxAttempts(), props.getRetry().getBackoffMs(),
                    props.getMemory().getWorkers(), props.getMemory().getQueueCapacity());
        } else {
            log.info("消息实现 {} 由自身构造参数完成配置，跳过注入", sender.mode());
        }
    }
}
