package com.aiteaching.kb.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * H2 文件库可写性预检（启动诊断）。
 *
 * <p><b>为什么需要它</b>：Windows 上存在一种受限令牌/强制完整性级别（Low IL + Mandatory Label NW）环境，
 * 此时 {@link Files#isWritable(Path)} 会对<b>所有</b>文件返回 false，而真实写入其实是被允许的
 * （NIO / FileOutputStream 都能成功）。H2 的 {@code Engine.openSession} 恰好用
 * {@code Files.isWritable} 判断，一旦返回 false 就把连接强制成 {@code ACCESS_MODE_DATA=r}
 * （且会覆盖 URL 里显式写的 {@code rw}），于是<b>第二次打开已有库时任何写入都抛
 * {@code 90097 The database is read only}</b>。</p>
 *
 * <p>本类不改变任何行为（不偷偷换数据源），只在启动时把结论讲清楚，
 * 让运维一眼知道该删库重建还是在沙箱外运行，而不是对着 90097 猜。</p>
 */
@Component
public class H2StorageDiagnostic {

    private static final Logger log = LoggerFactory.getLogger(H2StorageDiagnostic.class);

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @PostConstruct
    public void check() {
        Path dbFile = resolveDbFile(datasourceUrl);
        if (dbFile == null) {
            log.info("数据源不是 H2 文件库（url={}），跳过本地可写性预检", datasourceUrl);
            return;
        }
        boolean exists = Files.exists(dbFile);
        boolean jvmSaysWritable = Files.isWritable(dbFile);
        boolean fileApiSaysWritable = dbFile.toFile().canWrite();

        if (!exists) {
            log.info("H2 文件库首次创建: {}（首次运行写入正常）", dbFile.toAbsolutePath());
            return;
        }
        if (jvmSaysWritable) {
            log.info("H2 文件库可写性预检通过: {}", dbFile.toAbsolutePath());
            return;
        }

        boolean realWriteWorks = probeWrite(dbFile);
        if (realWriteWorks && fileApiSaysWritable) {
            log.warn("""
                    检测到受限运行环境：H2 文件库已存在但 java.nio.Files.isWritable() 返回 false，
                    而实际写入是成功的（File.canWrite={}、NIO 探测写入成功）。H2 会据此把本次连接
                    强制设为只读（ACCESS_MODE_DATA=r，会覆盖 URL 中的 rw），因此对已有库的写入会报
                    SQLState 90097 "The database is read only"；读取（GET 文档/切片/历史）不受影响。

                    H2 文件库: {}
                      处理方式（任选其一）：
                        1) 在普通（非沙箱/非受限令牌）终端中运行 run.cmd，即可正常写入并跨重启保留；
                        2) 若只需在受限环境中演示完整写入链路，删除该文件后重启（首轮运行写入可用）：
                           del "{}"
                    本提示不改变任何自动行为，仅作诊断说明。""",
                    fileApiSaysWritable, dbFile.toAbsolutePath(), dbFile.toAbsolutePath());
        } else {
            log.error("H2 文件库不可写（isWritable=false, canWrite={}, NIO 探测写入={}）：{}",
                    fileApiSaysWritable, realWriteWorks ? "成功" : "失败", dbFile.toAbsolutePath());
        }
    }

    /** 在库文件同目录做一次真实写入探测（成功即说明是 isWritable 误判，而非权限真的受限）。 */
    private boolean probeWrite(Path dbFile) {
        Path probe = dbFile.resolveSibling(".kb-write-probe.tmp");
        try {
            Files.write(probe, "probe".getBytes(), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(probe);
            return true;
        } catch (IOException e) {
            log.debug("NIO 探测写入失败: {}", e.getMessage());
            try {
                Files.deleteIfExists(probe);
            } catch (IOException ignored) {
                // 清理失败无影响
            }
            return false;
        }
    }

    /** 从 jdbc:h2:file:./data/kb;MODE=MySQL 解析出 ./data/kb.mv.db。 */
    private Path resolveDbFile(String url) {
        if (url == null || !url.startsWith("jdbc:h2:file:")) {
            return null;
        }
        String rest = url.substring("jdbc:h2:file:".length());
        int semi = rest.indexOf(';');
        if (semi >= 0) {
            rest = rest.substring(0, semi);
        }
        // 相对路径按 JVM 工作目录解析（与 H2 一致）
        return Paths.get(rest + ".mv.db").toAbsolutePath();
    }
}
