package com.aiteaching.kb.service.impl;

import com.aiteaching.kb.common.BusinessException;
import com.aiteaching.kb.config.KbProperties;
import com.aiteaching.kb.entity.UploadSession;
import com.aiteaching.kb.mapper.UploadSessionMapper;
import com.aiteaching.kb.mapper.UploadChunkMapper;
import com.aiteaching.kb.entity.UploadChunk;
import com.aiteaching.kb.service.DocService;
import com.aiteaching.kb.service.TextExtractor;
import com.aiteaching.kb.service.UploadService;
import com.aiteaching.kb.service.dto.UploadInitRequest;
import com.aiteaching.kb.service.dto.UploadInitResult;
import com.aiteaching.kb.service.dto.UploadProgress;
import com.aiteaching.kb.service.dto.UploadResult;
import com.aiteaching.kb.service.upload.ChunkStorage;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 分片上传（断点续传）实现。
 *
 * <p><b>三段式协议</b>（业界通行做法，与 DoVideoAI 类项目的"初始化 → 传片 → 合并"一致）：</p>
 * <ol>
 *   <li><b>POST /init</b> 分配 uploadId 与分片数（大文件先算出总片数，并做秒传预判）；</li>
 *   <li><b>PUT /{uploadId}/chunks/{seq}</b> 逐个上传分片，同一 seq 重传即覆盖（幂等）；</li>
 *   <li><b>POST /{uploadId}/merge</b> 校验齐备 → 合并（整文件 MD5 强校验）→ 接入导入流水线。</li>
 * </ol>
 *
 * <p><b>断点续传怎么做到</b>：进度权威来源是 {@code upload_chunks} 表（每收一片插一行）+ 磁盘
 * 分片文件。<b>不依赖客户端上报、也不依赖 Redis</b>——服务重启后 {@code GET /progress} 依然能
 * 准确告诉客户端"哪些片还缺"。客户端只补差集，已传分片零浪费。</p>
 *
 * <p><b>并发安全</b>：同一 uploadId 的 merge 用会话行状态 CAS（UPLOADING → MERGED）防止并发
 * 重复合并产生两份文档；分片写入是"先写 tmp 再原子改名"，避免半个分片被当成完整分片。</p>
 */
@Service
public class UploadServiceImpl implements UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadServiceImpl.class);

    private final UploadSessionMapper sessionMapper;
    private final UploadChunkMapper chunkMapper;
    private final ChunkStorage chunkStorage;
    private final DocService docService;
    private final KbProperties props;

    public UploadServiceImpl(UploadSessionMapper sessionMapper, UploadChunkMapper chunkMapper,
                             ChunkStorage chunkStorage, DocService docService, KbProperties props) {
        this.sessionMapper = sessionMapper;
        this.chunkMapper = chunkMapper;
        this.chunkStorage = chunkStorage;
        this.docService = docService;
        this.props = props;
    }

    @Override
    @Transactional
    public UploadInitResult init(UploadInitRequest request) {
        if (request == null || request.getFilename() == null || request.getFilename().isBlank()) {
            throw new BusinessException(400, "filename 不能为空");
        }
        if (request.getTotalSize() <= 0) {
            throw new BusinessException(400, "totalSize 必须为正数");
        }
        // 类型校验提前到 init：避免客户端白传几个 GB 才在合并时被拒
        String docType = TextExtractor.detectType(request.getFilename());

        int chunkSize = request.getChunkSize() != null && request.getChunkSize() > 0
                ? request.getChunkSize() : props.getUpload().getChunkSize();
        if (chunkSize > props.getUpload().getMaxChunkSize()) {
            throw new BusinessException(400, "chunkSize 超过上限 " + props.getUpload().getMaxChunkSize());
        }
        long maxFileSize = props.getUpload().getMaxFileSize();
        if (request.getTotalSize() > maxFileSize) {
            throw new BusinessException(400, "文件超过上限 " + maxFileSize + " 字节");
        }
        int totalChunks = (int) ((request.getTotalSize() + chunkSize - 1) / chunkSize);

        UploadInitResult result = new UploadInitResult();
        result.setChunkSize(chunkSize);
        result.setTotalChunks(totalChunks);

        // 秒传预判：客户端给了整文件 MD5 且库中已有同内容 → 一个分片都不用传
        if (request.getFileMd5() != null && !request.getFileMd5().isBlank()) {
            String user = normalizeUser(request.getUserId());
            // 复用整体上传的幂等语义：命中则一个分片都不用传
            UploadResult instant = docService.findDuplicateByMd5(request.getFileMd5(), user);
            if (instant != null) {
                result.setInstant(true);
                result.setDocId(instant.getDocId());
                result.setMessage("秒传命中：内容已存在（MD5 相同），无需上传分片");
                return result;
            }
        }

        String uploadId = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        LocalDateTime now = LocalDateTime.now();
        UploadSession session = new UploadSession();
        session.setUploadId(uploadId);
        session.setUserId(normalizeUser(request.getUserId()));
        session.setFilename(request.getFilename());
        session.setTotalSize(request.getTotalSize());
        session.setChunkSize(chunkSize);
        session.setTotalChunks(totalChunks);
        session.setFileMd5(request.getFileMd5());
        session.setStatus(UploadSession.STATUS_UPLOADING);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        session.setExpiresAt(now.plusHours(props.getUpload().getTtlHours()));
        sessionMapper.insert(session);

        result.setUploadId(uploadId);
        result.setMessage("初始化成功，请按 seq=0.." + (totalChunks - 1) + " 上传分片，随后 POST /merge");
        log.info("分片上传初始化 uploadId={} file={} size={} chunks={} chunkSize={}",
                uploadId, request.getFilename(), request.getTotalSize(), totalChunks, chunkSize);
        return result;
    }

    @Override
    @Transactional
    public UploadProgress receiveChunk(String uploadId, int seq, byte[] bytes, String chunkMd5, String userId) {
        UploadSession session = requireSession(uploadId, userId);
        if (!UploadSession.STATUS_UPLOADING.equals(session.getStatus())) {
            throw new BusinessException(409, "会话状态为 " + session.getStatus() + "，不可再接收分片");
        }
        if (seq < 0 || seq >= session.getTotalChunks()) {
            throw new BusinessException(400, "seq 越界：应在 0.." + (session.getTotalChunks() - 1) + " 之间，收到 " + seq);
        }
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(400, "分片内容为空");
        }
        // 分片大小校验：非末片必须等于 chunkSize（末片可小于）；否则说明客户端切片不一致，
        // 合并出来的文件会错位——这种错误越早发现越好
        boolean lastChunk = seq == session.getTotalChunks() - 1;
        int expectedMax = session.getChunkSize();
        if (!lastChunk && bytes.length != expectedMax) {
            throw new BusinessException(400, "非末分片大小必须为 " + expectedMax + "，收到 " + bytes.length);
        }
        if (bytes.length > expectedMax) {
            throw new BusinessException(400, "分片超过 chunkSize " + expectedMax);
        }

        // 分片级 MD5 校验：挡住"网络截断的坏分片"入库（坏片若进合并，只会表现为整文件 MD5 失败，
        // 定位成本高得多）
        String actualMd5 = md5Hex(bytes);
        if (chunkMd5 != null && !chunkMd5.isBlank() && !chunkMd5.equalsIgnoreCase(actualMd5)) {
            throw new BusinessException(400, "分片 MD5 校验失败 seq=" + seq
                    + "（客户端 " + chunkMd5 + " / 服务端 " + actualMd5 + "），请重传该分片");
        }

        // 先落盘，再写元数据：若元数据写成功而文件没落盘，进度就会撒谎
        chunkStorage.writeChunk(uploadId, seq, bytes);

        UploadChunk row = new UploadChunk();
        row.setUploadId(uploadId);
        row.setSeq(seq);
        row.setSizeBytes(bytes.length);
        row.setChunkMd5(actualMd5);
        row.setReceivedAt(LocalDateTime.now());
        try {
            chunkMapper.insert(row);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 客户端重传同一分片：覆盖更新（幂等），不报错
            chunkMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UploadChunk>()
                    .eq("upload_id", uploadId)
                    .eq("seq", seq)
                    .set("size_bytes", bytes.length)
                    .set("chunk_md5", actualMd5)
                    .set("received_at", LocalDateTime.now()));
            log.info("分片重传，已覆盖 uploadId={} seq={}", uploadId, seq);
        }
        sessionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UploadSession>()
                .eq("upload_id", uploadId)
                .set("updated_at", LocalDateTime.now()));

        return progress(uploadId, userId);
    }

    @Override
    public UploadProgress progress(String uploadId, String userId) {
        UploadSession session = requireSession(uploadId, userId);
        List<UploadChunk> rows = chunkMapper.selectList(new QueryWrapper<UploadChunk>()
                .eq("upload_id", uploadId).orderByAsc("seq"));
        List<Integer> received = new ArrayList<>();
        for (UploadChunk r : rows) {
            // DB 元数据 + 磁盘文件双确认：只信 DB 会在"文件被外部清理"时撒谎
            if (chunkStorage.chunkExists(uploadId, r.getSeq())) {
                received.add(r.getSeq());
            }
        }
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < session.getTotalChunks(); i++) {
            if (!received.contains(i)) {
                missing.add(i);
            }
        }
        UploadProgress p = new UploadProgress();
        p.setUploadId(uploadId);
        p.setFilename(session.getFilename());
        p.setStatus(session.getStatus());
        p.setTotalChunks(session.getTotalChunks());
        p.setReceivedCount(received.size());
        p.setReceivedSeqs(received);
        p.setMissingSeqs(missing);
        p.setPercent(session.getTotalChunks() == 0 ? 100 : (int) (received.size() * 100L / session.getTotalChunks()));
        p.setMergedDocId(session.getMergedDocId());
        p.setMergedTaskId(session.getMergedTaskId());
        return p;
    }

    @Override
    @Transactional
    public UploadResult merge(String uploadId, String userId) {
        UploadSession session = requireSession(uploadId, userId);

        // 幂等：已合并的会话重复调用直接回带原结果，不会产生第二份文档
        if (UploadSession.STATUS_MERGED.equals(session.getStatus())) {
            UploadResult r = new UploadResult();
            r.setDocId(session.getMergedDocId());
            r.setTaskId(session.getMergedTaskId());
            r.setFilename(session.getFilename());
            r.setMd5(session.getFileMd5());
            r.setMessage("该上传会话已合并（幂等返回原 docId/taskId）");
            return r;
        }
        if (!UploadSession.STATUS_UPLOADING.equals(session.getStatus())) {
            throw new BusinessException(409, "会话状态为 " + session.getStatus() + "，不可合并");
        }

        // 合并前必须齐备：不齐就明确告知缺哪些 seq，让客户端补传（这就是断点续传的收口）
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < session.getTotalChunks(); i++) {
            if (!chunkStorage.chunkExists(uploadId, i)) {
                missing.add(i);
            }
        }
        if (!missing.isEmpty()) {
            throw new BusinessException(400, "分片未齐备，缺失 seq=" + missing + "（共 "
                    + session.getTotalChunks() + " 片，已收 " + (session.getTotalChunks() - missing.size()) + " 片）");
        }

        // 状态 CAS：并发 merge 只有一个能成功，避免两份文档
        int updated = sessionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UploadSession>()
                .eq("upload_id", uploadId)
                .eq("status", UploadSession.STATUS_UPLOADING)
                .set("status", UploadSession.STATUS_MERGED)
                .set("updated_at", LocalDateTime.now()));
        if (updated == 0) {
            throw new BusinessException(409, "合并正在进行或已完成（并发保护），请稍后用 GET 进度查询结果");
        }

        String ext = extOf(session.getFilename());
        String docId = "d_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String actualMd5;
        try {
            actualMd5 = chunkStorage.merge(uploadId, session.getTotalChunks(), docId, ext, session.getFileMd5());
        } catch (RuntimeException e) {
            // 合并失败要回滚会话状态，否则会话卡在 MERGED 而实际上没有文档
            sessionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UploadSession>()
                    .eq("upload_id", uploadId)
                    .set("status", UploadSession.STATUS_UPLOADING)
                    .set("updated_at", LocalDateTime.now()));
            throw e;
        }

        // 接入既有导入流水线（切分/向量化/RAG 全链路复用）
        String storagePath = java.nio.file.Paths.get(ChunkStorage.FILE_DIR, docId + "." + ext.toLowerCase())
                .toAbsolutePath().toString();
        UploadResult result = docService.registerMergedFile(
                session.getFilename(), storagePath, actualMd5, session.getTotalSize(), session.getUserId());

        sessionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UploadSession>()
                .eq("upload_id", uploadId)
                .set("merged_doc_id", result.getDocId())
                .set("merged_task_id", result.getTaskId())
                .set("file_md5", actualMd5)
                .set("updated_at", LocalDateTime.now()));

        // 合并成功后清理分片，释放磁盘（正式原文已在 data/files 下）
        chunkStorage.cleanup(uploadId);
        chunkMapper.delete(new QueryWrapper<UploadChunk>().eq("upload_id", uploadId));

        log.info("分片上传合并完成 uploadId={} docId={} taskId={} md5={}",
                uploadId, result.getDocId(), result.getTaskId(), actualMd5);
        return result;
    }

    @Override
    @Transactional
    public void abort(String uploadId, String userId) {
        UploadSession session = requireSession(uploadId, userId);
        if (UploadSession.STATUS_MERGED.equals(session.getStatus())) {
            throw new BusinessException(409, "会话已合并，不可放弃");
        }
        sessionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UploadSession>()
                .eq("upload_id", uploadId)
                .set("status", UploadSession.STATUS_ABORTED)
                .set("updated_at", LocalDateTime.now()));
        chunkStorage.cleanup(uploadId);
        chunkMapper.delete(new QueryWrapper<UploadChunk>().eq("upload_id", uploadId));
        log.info("分片上传已放弃 uploadId={}", uploadId);
    }

    @Override
    @Transactional
    public int reapExpired() {
        List<UploadSession> expired = sessionMapper.selectList(new QueryWrapper<UploadSession>()
                .eq("status", UploadSession.STATUS_UPLOADING)
                .lt("expires_at", LocalDateTime.now()));
        int n = 0;
        for (UploadSession s : expired) {
            chunkStorage.cleanup(s.getUploadId());
            chunkMapper.delete(new QueryWrapper<UploadChunk>().eq("upload_id", s.getUploadId()));
            sessionMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<UploadSession>()
                    .eq("upload_id", s.getUploadId())
                    .set("status", UploadSession.STATUS_EXPIRED)
                    .set("updated_at", LocalDateTime.now()));
            n++;
        }
        if (n > 0) {
            log.info("回收过期上传会话 {} 个", n);
        }
        return n;
    }

    private UploadSession requireSession(String uploadId, String userId) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new BusinessException(400, "uploadId 不能为空");
        }
        UploadSession session = sessionMapper.selectOne(new QueryWrapper<UploadSession>()
                .eq("upload_id", uploadId).last("limit 1"));
        if (session == null) {
            throw new BusinessException(404, "上传会话不存在: " + uploadId);
        }
        // 归属校验：不同用户不能操作别人的上传会话（否则可越权合并出他人文件）
        String user = normalizeUser(userId);
        if (!user.equals(session.getUserId())) {
            throw new BusinessException(403, "无权操作该上传会话");
        }
        return session;
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }

    private static String extOf(String filename) {
        int i = filename.lastIndexOf('.');
        return i < 0 ? "txt" : filename.substring(i + 1);
    }

    private static String md5Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new BusinessException(500, "MD5 计算失败: " + e.getMessage(), e);
        }
    }
}
