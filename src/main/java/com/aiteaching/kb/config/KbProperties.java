package com.aiteaching.kb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 配置键总表（application.yml 中 kb.*）。
 */
@Component
@ConfigurationProperties(prefix = "kb")
public class KbProperties {

    private final Llm llm = new Llm();
    private final Mq mq = new Mq();
    private final Rag rag = new Rag();
    private final Redis redis = new Redis();
    private final RateLimit rateLimit = new RateLimit();
    private final Chat chat = new Chat();
    private final Upload upload = new Upload();

    public Llm getLlm() {
        return llm;
    }

    public Mq getMq() {
        return mq;
    }

    public Rag getRag() {
        return rag;
    }

    public Redis getRedis() {
        return redis;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Chat getChat() {
        return chat;
    }

    public Upload getUpload() {
        return upload;
    }

    public static class Llm {
        /** 对话模型 provider：mock | openai */
        private String provider = "mock";
        /**
         * 向量模型 provider：mock | openai | 留空（跟随 {@link #provider}）。
         *
         * <p><b>为什么必须能独立配置</b>：实测发现不少企业级 OpenAI 兼容网关只代理
         * {@code /chat/completions}，不提供 {@code /embeddings}（返回 404）。若两者绑死，
         * 用户为了用真实对话模型就必须放弃本地检索——把 chat 与 embedding 解耦后，
         * 可以"真实模型回答 + 本地向量检索"混合运行，RAG 全链路依然可用。</p>
         */
        private String embeddingProvider = "";
        private final OpenAi openai = new OpenAi();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getEmbeddingProvider() {
            return embeddingProvider;
        }

        public void setEmbeddingProvider(String embeddingProvider) {
            this.embeddingProvider = embeddingProvider;
        }

        public OpenAi getOpenai() {
            return openai;
        }
    }

    public static class OpenAi {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String chatModel = "gpt-4o-mini";
        private String embeddingModel = "text-embedding-3-small";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getChatModel() {
            return chatModel;
        }

        public void setChatModel(String chatModel) {
            this.chatModel = chatModel;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }
    }

    public static class Mq {
        /** memory | rocketmq */
        private String mode = "memory";
        private final RocketMq rocketmq = new RocketMq();

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public RocketMq getRocketmq() {
            return rocketmq;
        }
    }

    public static class RocketMq {
        private String namesrvAddr = "127.0.0.1:9876";
        private String topic = "KB_TASK";
        private String consumerGroup = "kb_consumer";

        public String getNamesrvAddr() {
            return namesrvAddr;
        }

        public void setNamesrvAddr(String namesrvAddr) {
            this.namesrvAddr = namesrvAddr;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public void setConsumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
        }
    }

    public static class Rag {
        private int chunkSize = 500;
        private int chunkOverlap = 50;
        private int topK = 5;
        private double scoreThreshold = 0.15;

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getScoreThreshold() {
            return scoreThreshold;
        }

        public void setScoreThreshold(double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
        }
    }

    public static class Redis {
        private String host = "127.0.0.1";
        private int port = 6379;
        private int timeoutMs = 2000;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public static class RateLimit {
        private int qps = 5;
        private int capacity = 10;

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
    }

    public static class Chat {
        private int historyTurns = 5;

        public int getHistoryTurns() {
            return historyTurns;
        }

        public void setHistoryTurns(int historyTurns) {
            this.historyTurns = historyTurns;
        }
    }

    /**
     * 分片上传参数（kb.upload.*）。
     *
     * <p>默认值取向：chunkSize 取 5MB（常见取值，兼顾请求开销与重传成本），
     * maxFileSize 取 2GB（"GB 级文件"场景），TTL 24 小时（跨天续传仍有效）。</p>
     */
    public static class Upload {
        /** 默认分片大小（字节），客户端可覆盖 */
        private int chunkSize = 5 * 1024 * 1024;
        /** 单分片上限（防客户端用超大分片绕过流控） */
        private int maxChunkSize = 32 * 1024 * 1024;
        /** 单文件上限（字节） */
        private long maxFileSize = 2L * 1024 * 1024 * 1024;
        /** 未完成会话的存活小时数，超时由定时任务回收分片 */
        private int ttlHours = 24;
        /** 过期回收任务的执行间隔（毫秒） */
        private long reapIntervalMs = 3600_000L;

        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getMaxChunkSize() { return maxChunkSize; }
        public void setMaxChunkSize(int maxChunkSize) { this.maxChunkSize = maxChunkSize; }
        public long getMaxFileSize() { return maxFileSize; }
        public void setMaxFileSize(long maxFileSize) { this.maxFileSize = maxFileSize; }
        public int getTtlHours() { return ttlHours; }
        public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }
        public long getReapIntervalMs() { return reapIntervalMs; }
        public void setReapIntervalMs(long reapIntervalMs) { this.reapIntervalMs = reapIntervalMs; }
    }
}
