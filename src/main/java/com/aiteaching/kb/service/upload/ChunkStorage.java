package com.aiteaching.kb.service.upload;

import com.aiteaching.kb.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 分片字节的落盘与合并（纯文件操作，不含业务状态）。
 *
 * <p>目录约定：</p>
 * <pre>
 *   data/chunks/&lt;uploadId&gt;/&lt;seq&gt;.part     # 单个分片
 *   data/chunks/&lt;uploadId&gt;/_merged.tmp      # 合并过程中的临时文件
 *   data/files/&lt;docId&gt;.&lt;ext&gt;                # 合并完成后的正式原文（与整体上传共用同一目录）
 * </pre>
 *
 * <p><b>为什么要 _merged.tmp 再原子改名</b>：合并过程可能因磁盘满/进程被杀而中断。若直接往
 * 最终路径写，中断后留下的就是一个"看起来完整、实际截断"的文件，后续校验要花很大代价才能发现。
 * 先写临时文件、成功后再 {@link Files#move} 原子替换，可保证最终路径要么是完整文件、要么不存在。</p>
 */
@Component
public class ChunkStorage {

    private static final Logger log = LoggerFactory.getLogger(ChunkStorage.class);

    /** 分片根目录 */
    public static final String CHUNK_DIR = "data/chunks";
    /** 合并后的原文目录（与整体上传共用，保持"文档原文只有一处"） */
    public static final String FILE_DIR = "data/files";

    /** 分片文件路径。 */
    public Path chunkPath(String uploadId, int seq) {
        return Paths.get(CHUNK_DIR, uploadId, seq + ".part");
    }

    /** 写入一个分片（覆盖语义：同 seq 重传即覆盖，天然幂等）。 */
    public void writeChunk(String uploadId, int seq, byte[] bytes) {
        Path target = chunkPath(uploadId, seq);
        try {
            Files.createDirectories(target.getParent());
            // 分片通常 1~5MB，整块写；先写临时再原子改名，避免"半个分片"被当成完整分片
            Path tmp = target.resolveSibling(seq + ".part.tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new BusinessException(500, "分片写入失败 seq=" + seq + ": " + e.getMessage(), e);
        }
    }

    /** 分片是否已存在于磁盘（续传只信磁盘 + DB 元数据，不信客户端上报）。 */
    public boolean chunkExists(String uploadId, int seq) {
        return Files.isRegularFile(chunkPath(uploadId, seq));
    }

    public long chunkSizeOnDisk(String uploadId, int seq) {
        try {
            return Files.size(chunkPath(uploadId, seq));
        } catch (IOException e) {
            return -1L;
        }
    }

    /**
     * 按 seq 顺序合并全部分片到 {@code data/files/&lt;docId&gt;.&lt;ext&gt;}，边合并边算 MD5。
     *
     * @param expectedMd5 客户端声明的整文件 MD5；非空时做合并后强校验（不匹配抛错并清理产物）
     * @return 合并后的实际 MD5（小写 hex）
     */
    public String merge(String uploadId, int totalChunks, String docId, String ext, String expectedMd5) {
        Path dir = Paths.get(CHUNK_DIR, uploadId);
        Path mergedTmp = dir.resolve("_merged.tmp");
        Path finalFile = Paths.get(FILE_DIR, docId + "." + ext.toLowerCase());

        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            throw new BusinessException(500, "MD5 不可用: " + e.getMessage(), e);
        }

        try {
            Files.createDirectories(dir);
            Files.createDirectories(finalFile.getParent());
            try (OutputStream out = Files.newOutputStream(mergedTmp)) {
                for (int seq = 0; seq < totalChunks; seq++) {
                    Path part = chunkPath(uploadId, seq);
                    if (!Files.isRegularFile(part)) {
                        throw new BusinessException(400, "分片缺失，无法合并：seq=" + seq
                                + "（请先补传该分片，或用 GET 进度接口核对）");
                    }
                    try (InputStream in = Files.newInputStream(part)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            md5.update(buf, 0, n);
                            out.write(buf, 0, n);
                        }
                    }
                }
            }
            String actual = HexFormat.of().formatHex(md5.digest());

            if (expectedMd5 != null && !expectedMd5.isBlank() && !expectedMd5.equalsIgnoreCase(actual)) {
                // 校验失败：清理临时产物不留半成品；磁盘分片保留，便于定位坏片
                Files.deleteIfExists(mergedTmp);
                throw new BusinessException(400, "整文件 MD5 校验失败：客户端声明 " + expectedMd5
                        + "，服务端合并结果 " + actual + "（分片已保留，可重传可疑分片后再次合并）");
            }

            // 原子改名：最终路径要么完整、要么不存在
            Files.move(mergedTmp, finalFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("分片合并完成 uploadId={} chunks={} -> {} md5={}", uploadId, totalChunks, finalFile, actual);
            return actual;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(mergedTmp);
            } catch (IOException ignore) {
                // 清理失败不影响主流程：下次合并会覆盖
            }
            throw new BusinessException(500, "分片合并失败: " + e.getMessage(), e);
        }
    }

    /** 清理某个上传会话的全部分片（合并成功、放弃或过期回收时调用）。 */
    public void cleanup(String uploadId) {
        Path dir = Paths.get(CHUNK_DIR, uploadId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // 尽力而为：残留由 TTL 清理任务兜底
                }
            });
            log.info("分片目录已清理 uploadId={}", uploadId);
        } catch (IOException e) {
            log.warn("分片清理失败 uploadId={}: {}", uploadId, e.getMessage());
        }
    }
}
