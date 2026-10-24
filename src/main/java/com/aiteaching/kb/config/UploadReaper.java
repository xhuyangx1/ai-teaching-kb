package com.aiteaching.kb.config;

import com.aiteaching.kb.service.UploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 过期上传会话回收：把 {@code status=UPLOADING 且 expires_at < now} 的会话清理掉
 * （分片目录 + 元数据 + 会话置 EXPIRED）。
 *
 * <p>为什么必须有这个：客户端可能传了一半就再也不来了（关浏览器、断网、程序崩溃）。
 * 没有回收，磁盘上就会永久堆积孤儿分片——这是分片上传最容易漏掉的运维收口。</p>
 */
@Component
public class UploadReaper {

    private static final Logger log = LoggerFactory.getLogger(UploadReaper.class);

    private final UploadService uploadService;

    public UploadReaper(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @Scheduled(fixedDelayString = "${kb.upload.reap-interval-ms:3600000}", initialDelay = 300_000)
    public void reap() {
        try {
            int n = uploadService.reapExpired();
            if (n > 0) {
                log.info("过期上传会话回收完成，共 {} 个", n);
            }
        } catch (Exception e) {
            // 回收失败不能影响主流程
            log.warn("过期上传会话回收异常: {}", e.getMessage());
        }
    }
}
