package com.aiteaching.kb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AI 教学知识库平台启动类。
 *
 * <p>离线可跑：默认 H2 文件库 + memory 队列 + mock LLM，不需要任何 API key / 中间件。</p>
 *
 * <p>{@code @EnableScheduling} 用于过期上传会话回收（分片上传的运维收口，
 * 防止客户端传一半就跑导致孤儿分片永久占盘）。</p>
 */
@SpringBootApplication
@EnableScheduling
public class KbApplication {

    public static void main(String[] args) {
        SpringApplication.run(KbApplication.class, args);
    }
}
