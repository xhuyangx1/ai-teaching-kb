package com.aiteaching.kb.mq;

import com.aiteaching.kb.config.MqProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 默认（离线）消息实现：每个 topic 一个 {@link LinkedBlockingQueue} + 消费线程池。
 *
 * <p>刻意保持与真实 MQ 相同的语义，避免"内存版"退化成同步调用：</p>
 * <ul>
 *   <li><b>异步投递</b>：{@code send} 只入队立即返回，业务处理发生在消费线程，
 *       因此导入接口能在几十毫秒内返回 PENDING 任务号；</li>
 *   <li><b>失败重试</b>：指数退避，次数由 {@code kb.mq.retry.max-attempts} 配置；</li>
 *   <li><b>死信</b>：超过上限进死信 —— 日志 + 任务置 FAILED（含 failReason），可查
 *       {@code GET /api/tasks/{taskId}}，并保留内存快照供诊断；</li>
 *   <li><b>背压</b>：队列有界，满时拒绝入队并标记任务失败，不静默丢消息。</li>
 * </ul>
 *
 * <p>线程池为守护线程，应用退出不会挂住 JVM；{@link #shutdown()} 会优雅停止。</p>
 */
public class InMemoryMessageSender implements MessageSender, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMessageSender.class);

    private final ImportTaskConsumer consumer;
    private final java.util.concurrent.atomic.AtomicReference<MqProperties> propsRef =
            new java.util.concurrent.atomic.AtomicReference<>(new MqProperties());

    /** 便捷读取，始终非空。 */
    private MqProperties props() {
        MqProperties p = propsRef.get();
        return p == null ? new MqProperties() : p;
    }

    private final Map<String, LinkedBlockingQueue<String>> queues = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong deadLetters = new AtomicLong();

    private ExecutorService pool;

    public InMemoryMessageSender(ImportTaskConsumer consumer) {
        this(consumer, new MqProperties());
    }

    public InMemoryMessageSender(ImportTaskConsumer consumer, MqProperties props) {
        this.consumer = consumer;
        if (props != null) {
            this.propsRef.set(props);
        }
    }

    @Override
    public void afterPropertiesSet() {
        if (pool == null) {
            int workers = Math.max(1, props().getMemory().getWorkers());
            this.pool = new ThreadPoolExecutor(workers, workers, 60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "kb-mq-consumer");
                t.setDaemon(true);
                return t;
            });
            log.info("内存队列已就绪 workers={} queueCapacity={} retryMaxAttempts={}",
                    workers, props().getMemory().getQueueCapacity(), props().getRetry().getMaxAttempts());
        }
    }

    @Override
    public void send(String topic, String jsonPayload) {
        ensurePool();
        String key = topic == null ? TOPIC_KB_TASK : topic;
        LinkedBlockingQueue<String> q = queues.computeIfAbsent(key, k -> {
            LinkedBlockingQueue<String> created = new LinkedBlockingQueue<>(capacity());
            startConsumer(k, created);
            return created;
        });
        if (!q.offer(jsonPayload)) {
            rejected.incrementAndGet();
            // 队列满属于系统性过载：必须显式暴露，不能静默丢弃导致任务永远 PENDING
            log.error("内存队列已满，拒绝入队 topic={} capacity={}", key, capacity());
            throw new IllegalStateException("内存队列已满（topic=" + key + ", capacity=" + capacity()
                    + "），请提高 kb.mq.memory.queue-capacity 或启用真实 MQ");
        }
        accepted.incrementAndGet();
        log.debug("消息入队 topic={} queueSize={}", key, q.size());
    }

    private int capacity() {
        return Math.max(1, props().getMemory().getQueueCapacity());
    }

    private void ensurePool() {
        if (pool == null) {
            synchronized (this) {
                if (pool == null) {
                    try {
                        afterPropertiesSet();
                    } catch (Exception e) {
                        throw new IllegalStateException("初始化内存队列线程池失败", e);
                    }
                }
            }
        }
    }

    private void startConsumer(String topic, LinkedBlockingQueue<String> queue) {
        pool.submit(() -> {
            log.info("消费线程启动 topic={}", topic);
            while (running.get()) {
                String payload = null;
                try {
                    payload = queue.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("消费线程被中断退出 topic={}", topic);
                    return;
                }
                if (payload == null) {
                    continue;
                }
                try {
                    boolean ok = consumer.consumeWithRetry(payload);
                    if (!ok) {
                        deadLetters.incrementAndGet();
                        log.error("消息最终失败进入死信 topic={} payload={}", topic, payload);
                    }
                } catch (Exception e) {
                    // 消费者自身抛异常不应打死消费线程
                    log.error("消费线程异常（已隔离，线程继续运行）topic={} payload={}", topic, payload, e);
                }
            }
        });
    }

    @Override
    public String mode() {
        return "memory";
    }

    /** 已成功入队的消息数。 */
    public long getAccepted() {
        return accepted.get();
    }

    /** 因队列满被拒绝的消息数。 */
    public long getRejected() {
        return rejected.get();
    }

    /** 进入死信的消息数（与 {@link ImportTaskConsumer#getDeadLetterCount()} 口径一致）。 */
    public long getDeadLetters() {
        return deadLetters.get();
    }

    /** 当前各 topic 排队深度。 */
    public Map<String, Integer> queueDepths() {
        Map<String, Integer> out = new ConcurrentHashMap<>();
        queues.forEach((k, v) -> out.put(k, v.size()));
        return out;
    }

    /**
     * 应用外部配置（由 {@link MqWiringConfigurer} 在容器启动完成时调用）。
     *
     * <p>仅在消费线程尚未启动时才会替换线程池参数；队列一旦建立就沿用原容量，
     * 避免运行期改变队列边界导致未定义行为。</p>
     */
    public synchronized void configure(MqProperties newProps) {
        if (newProps == null) {
            return;
        }
        this.propsRef.set(newProps);
        if (pool == null && !queues.isEmpty()) {
            log.warn("队列已建立，新的 queueCapacity 不会作用于已存在的 topic");
        }
    }

    /** 当前生效的配置（只读视图）。 */
    public MqProperties currentProps() {
        return propsRef.get();
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("内存队列关闭中 accepted={} rejected={} deadLetters={}", accepted.get(), rejected.get(), deadLetters.get());
        if (pool != null) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
