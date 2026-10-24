package com.aiteaching.kb.service.impl;

import com.aiteaching.kb.common.BusinessException;
import com.aiteaching.kb.config.KbProperties;
import com.aiteaching.kb.entity.Chunk;
import com.aiteaching.kb.entity.Document;
import com.aiteaching.kb.entity.Task;
import com.aiteaching.kb.mapper.ChunkMapper;
import com.aiteaching.kb.mapper.DocumentMapper;
import com.aiteaching.kb.rag.embed.KbEmbeddingModel;
import com.aiteaching.kb.rag.embed.VectorCodec;
import com.aiteaching.kb.rag.splitter.DocSplitter;
import com.aiteaching.kb.rag.splitter.SplitChunk;
import com.aiteaching.kb.rag.store.VectorStore;
import com.aiteaching.kb.service.DocService;
import com.aiteaching.kb.service.TaskService;
import com.aiteaching.kb.service.TextExtractor;
import com.aiteaching.kb.service.dto.ChunkView;
import com.aiteaching.kb.service.dto.UploadResult;
import com.aiteaching.kb.common.JsonUtil;
import com.aiteaching.kb.mq.TaskPublisher;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 文档服务实现。
 *
 * <p>关键设计：</p>
 * <ul>
 *   <li><b>MD5 幂等</b>：uk_documents_user_md5 唯一约束 + 上传前查询双重保护，重复上传不产生新文档；</li>
 *   <li><b>导入异步</b>：HTTP 只做落盘 + 建任务 + 投递，切分/向量化在消费线程完成，接口立即返回 taskId；</li>
 *   <li><b>原文落盘</b>：{@code data/files/<docId>.<ext>}，重新切分只需重放任务（向量可重建）。</li>
 * </ul>
 */
@Service
public class DocServiceImpl implements DocService {

    private static final Logger log = LoggerFactory.getLogger(DocServiceImpl.class);
    private static final String STORAGE_DIR = "data/files";
    private static final int EMBED_BATCH = 100;

    private final DocumentMapper documentMapper;
    private final ChunkMapper chunkMapper;
    private final DocSplitter docSplitter;
    private final TextExtractor textExtractor;
    private final KbEmbeddingModel embeddingModel;
    private final TaskService taskService;
    private final TaskPublisher taskPublisher;
    private final VectorStore vectorStore;
    private final KbProperties props;

    public DocServiceImpl(DocumentMapper documentMapper, ChunkMapper chunkMapper, DocSplitter docSplitter,
                          TextExtractor textExtractor, KbEmbeddingModel embeddingModel, TaskService taskService,
                          TaskPublisher taskPublisher, VectorStore vectorStore, KbProperties props) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.docSplitter = docSplitter;
        this.textExtractor = textExtractor;
        this.embeddingModel = embeddingModel;
        this.taskService = taskService;
        this.taskPublisher = taskPublisher;
        this.vectorStore = vectorStore;
        this.props = props;
    }

    @Override
    @Transactional
    public UploadResult upload(MultipartFile file, String userId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "上传文件为空");
        }
        String user = normalizeUser(userId);
        String filename = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
        String docType = TextExtractor.detectType(filename);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(500, "读取上传文件失败: " + e.getMessage(), e);
        }
        String md5 = md5Hex(bytes);

        // 不带 status 过滤地回查：唯一约束 uk_documents_user_md5 覆盖所有行（含已逻辑删除的行），
        // 若只查 ACTIVE，就会在"上传→删除→再上传同一内容"时走到 insert 并撞唯一约束。
        Document anyExisting = findByMd5(user, md5);
        if (anyExisting != null) {
            if (!"DELETED".equals(anyExisting.getStatus())) {
                log.info("MD5 幂等命中 user={} md5={} docId={}", user, md5, anyExisting.getDocId());
                return duplicateResult(anyExisting, md5, "内容与已有文档相同（MD5 命中），已返回原文档，未重复导入");
            }
            // 复活：内容曾上传后被删除，本次重新导入 -> 复用原 docId 并重建导入任务
            log.info("MD5 命中已删除文档，执行复活导入 user={} md5={} docId={}", user, md5, anyExisting.getDocId());
            return reviveAndSchedule(anyExisting, user, filename, docType, bytes, md5, Task.TYPE_IMPORT_DOC);
        }

        String docId = "d_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String storagePath = saveToDisk(docId, docType, bytes);

        Document doc = new Document();
        doc.setDocId(docId);
        doc.setUserId(user);
        doc.setFilename(filename);
        doc.setDocType(docType);
        doc.setContentMd5(md5);
        doc.setStoragePath(storagePath);
        doc.setSizeBytes((long) bytes.length);
        doc.setStatus("ACTIVE");
        LocalDateTime now = LocalDateTime.now();
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        try {
            documentMapper.insert(doc);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发上传同一文件：唯一约束兜底。回查时同样不带 status 过滤，
            // 否则"并发 + 该行已被删除"的组合仍会退化成 500。
            Document raceWinner = findByMd5(user, md5);
            if (raceWinner == null) {
                throw new BusinessException(500, "文档入库失败（唯一约束冲突且无法回查）", e);
            }
            if (!"DELETED".equals(raceWinner.getStatus())) {
                log.info("并发上传命中唯一约束，回退幂等 docId={}", raceWinner.getDocId());
                return duplicateResult(raceWinner, md5, "内容与已有文档相同（并发命唯一约束），已返回原文档，未重复导入");
            }
            log.info("并发上传命中已删除行，执行复活导入 docId={}", raceWinner.getDocId());
            return reviveAndSchedule(raceWinner, user, filename, docType, bytes, md5, Task.TYPE_IMPORT_DOC);
        }

        Task task = taskService.createTask(Task.TYPE_IMPORT_DOC, docId,
                JsonUtil.toJson(java.util.Map.of("docId", docId, "docType", docType, "filename", filename)));

        UploadResult result = new UploadResult();
        result.setDocId(docId);
        result.setFilename(filename);
        result.setDocType(docType);
        result.setMd5(md5);
        result.setSizeBytes(bytes.length);
        result.setDuplicate(false);
        result.setTaskId(task.getTaskId());
        result.setTaskStatus(task.getStatus());

        // 关键：不在事务内直接投递，而是登记"提交后投递"。
        // 若在事务提交前就把消息发出去，消费线程可能抢先回查任务行——那时行还没提交，
        // 消费者查不到任务会把消息当脏数据丢掉，任务就永远停在 PENDING。
        taskPublisher.publish(task);
        return result;
    }

    @Override
    @Transactional
    public UploadResult importChatDocument(String filename, String content, String userId) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(400, "聊天记录内容为空");
        }
        String user = normalizeUser(userId);
        String type = "CHAT";
        String md5 = md5Hex(content.getBytes(StandardCharsets.UTF_8));
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        String finalName = filename == null ? "chat-import.json" : filename;
        // 与 upload() 同理：回查不带 status 过滤，删除后再导入同一份聊天记录同样要能复活。
        Document anyExisting = findByMd5(user, md5);
        if (anyExisting != null && !"DELETED".equals(anyExisting.getStatus())) {
            return duplicateResult(anyExisting, md5, "聊天记录内容与已有文档相同（MD5 命中），未重复导入");
        }
        if (anyExisting != null) {
            log.info("聊天记录命中已删除文档，执行复活导入 docId={}", anyExisting.getDocId());
            return reviveAndSchedule(anyExisting, user, finalName, type, payload, md5, Task.TYPE_IMPORT_CHAT);
        }
        String docId = "d_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String storagePath = saveToDisk(docId, type, payload);
        Document doc = new Document();
        doc.setDocId(docId);
        doc.setUserId(user);
        doc.setFilename(finalName);
        doc.setDocType(type);
        doc.setContentMd5(md5);
        doc.setStoragePath(storagePath);
        doc.setSizeBytes((long) payload.length);
        doc.setStatus("ACTIVE");
        LocalDateTime now = LocalDateTime.now();
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        try {
            documentMapper.insert(doc);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发写入同一份聊天记录：唯一约束兜底（原实现缺此 catch，会直接冒成 500）
            Document raceWinner = findByMd5(user, md5);
            if (raceWinner == null) {
                throw new BusinessException(500, "聊天文档入库失败（唯一约束冲突且无法回查）", e);
            }
            if (!"DELETED".equals(raceWinner.getStatus())) {
                return duplicateResult(raceWinner, md5, "聊天记录内容与已有文档相同（并发命唯一约束），未重复导入");
            }
            return reviveAndSchedule(raceWinner, user, finalName, type, payload, md5, Task.TYPE_IMPORT_CHAT);
        }

        Task task = taskService.createTask(Task.TYPE_IMPORT_CHAT, docId,
                JsonUtil.toJson(java.util.Map.of("docId", docId, "docType", type, "filename", doc.getFilename())));
        UploadResult result = new UploadResult();
        result.setDocId(docId);
        result.setFilename(doc.getFilename());
        result.setDocType(type);
        result.setMd5(md5);
        result.setSizeBytes(doc.getSizeBytes());
        result.setTaskId(task.getTaskId());
        result.setTaskStatus(task.getStatus());

        taskPublisher.publish(task);
        return result;
    }

    @Override
    @Transactional
    public void logicalDelete(String docId) {
        Document doc = getByDocId(docId);
        if (doc == null) {
            throw new BusinessException(404, "文档不存在: " + docId);
        }
        documentMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Document>()
                .eq("doc_id", docId)
                .set("status", "DELETED")
                .set("updated_at", LocalDateTime.now()));
        chunkMapper.delete(new QueryWrapper<Chunk>().eq("doc_id", docId));
        vectorStore.invalidate();
        log.info("文档已逻辑删除并清理切片 docId={}", docId);
    }

    @Override
    public List<ChunkView> listChunks(String docId) {
        List<Chunk> chunks = chunkMapper.selectList(new QueryWrapper<Chunk>().eq("doc_id", docId).orderByAsc("seq"));
        List<ChunkView> out = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            out.add(ChunkView.of(c));
        }
        return out;
    }

    @Override
    public Document getByDocId(String docId) {
        if (docId == null) {
            return null;
        }
        return documentMapper.selectOne(new QueryWrapper<Document>().eq("doc_id", docId));
    }

    @Override
    public List<Document> listActive() {
        return documentMapper.selectList(new QueryWrapper<Document>().eq("status", "ACTIVE"));
    }

    @Override
    public void processImport(Task task) {
        String docId = task.getDocId();
        Document doc = getByDocId(docId);
        if (doc == null) {
            throw new BusinessException(404, "任务对应的文档不存在: " + docId);
        }
        if ("DELETED".equals(doc.getStatus())) {
            log.info("文档已删除，跳过导入 docId={}", docId);
            return;
        }
        taskService.updateProgress(task.getTaskId(), 10);

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(Paths.get(doc.getStoragePath()));
        } catch (IOException e) {
            throw new BusinessException(500, "读取原文失败: " + doc.getStoragePath(), e);
        }
        taskService.updateProgress(task.getTaskId(), 25);

        String text = textExtractor.extract(doc.getDocType(), bytes);
        if (text == null || text.isBlank()) {
            throw new BusinessException(400, "文档解析结果为空（可能是空文件或格式异常）");
        }
        taskService.updateProgress(task.getTaskId(), 45);

        List<SplitChunk> chunks = docSplitter.split(text);
        if (chunks.isEmpty()) {
            throw new BusinessException(400, "切分结果为空，无法建立向量索引");
        }
        taskService.updateProgress(task.getTaskId(), 60);
        persistChunks(docId, chunks, 60);
        taskService.updateProgress(task.getTaskId(), 100);
        log.info("文档导入完成 docId={} chunks={}", docId, chunks.size());
    }

    @Override
    public int persistChunks(String docId, List<SplitChunk> splitChunks, int progressFrom) {
        // 重建语义：先清空该文档的旧切片，避免重复导入产生脏数据
        chunkMapper.delete(new QueryWrapper<Chunk>().eq("doc_id", docId));
        List<Chunk> buffer = new ArrayList<>(EMBED_BATCH);
        int seq = 0;
        int total = splitChunks.size();
        for (SplitChunk sc : splitChunks) {
            float[] vec = embeddingModel.embed(sc.getContent());
            Chunk c = new Chunk();
            c.setChunkId(genId());
            c.setDocId(docId);
            c.setSeq(seq++);
            c.setContent(sc.getContent());
            c.setSpeaker(sc.getSpeaker());
            c.setMsgTime(sc.getMsgTime());
            c.setEmbedding(VectorCodec.encode(vec));
            c.setDim(vec.length);
            c.setTokenCount(Math.max(1, sc.getContent().length() / 2));
            c.setCreatedAt(LocalDateTime.now());
            buffer.add(c);
            if (buffer.size() >= EMBED_BATCH) {
                buffer.forEach(chunkMapper::insert);
                buffer.clear();
            }
        }
        buffer.forEach(chunkMapper::insert);
        vectorStore.invalidate();
        return total;
    }

    private static String genId() {
        return "c_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    /**
     * 按 (user_id, content_md5) 回查文档，<b>不带 status 过滤</b>。
     *
     * <p>唯一约束 {@code uk_documents_user_md5} 覆盖该用户的所有行（含已逻辑删除的行），
     * 因此幂等判断也必须按同样范围回查；只查 ACTIVE 会导致"上传→删除→再上传同一内容"
     * 走到 insert 并撞唯一约束（历史缺陷 F1）。</p>
     */
    private Document findByMd5(String userId, String md5) {
        return documentMapper.selectOne(new QueryWrapper<Document>()
                .eq("user_id", userId)
                .eq("content_md5", md5)
                .orderByDesc("id")
                .last("limit 1"));
    }

    /** 组装"幂等命中"响应（文档仍在 ACTIVE，不新建导入任务）。 */
    private UploadResult duplicateResult(Document existing, String md5, String message) {
        UploadResult r = new UploadResult();
        r.setDocId(existing.getDocId());
        r.setFilename(existing.getFilename());
        r.setDocType(existing.getDocType());
        r.setMd5(md5);
        r.setSizeBytes(existing.getSizeBytes() == null ? 0 : existing.getSizeBytes());
        r.setDuplicate(true);
        String taskId = latestTaskId(existing.getDocId());
        r.setTaskId(taskId);
        r.setTaskStatus(statusOf(taskId));
        r.setMessage(message);
        return r;
    }

    /**
     * 复活导入：内容曾上传过、后被逻辑删除，本次重新导入同一内容。
     *
     * <p>为什么复用原 docId 而不是插新行：{@code uk_documents_user_md5} 是
     * (user_id, content_md5) 唯一约束，已删除的行仍然占用该键，插新行必然冲突。
     * 因此把原行重新置为 ACTIVE、刷新文件名/大小/路径与 updated_at，
     * 再为它新建一个导入任务（切片与向量在消费线程重建）。</p>
     */
    private UploadResult reviveAndSchedule(Document row, String user, String filename, String docType,
                                           byte[] bytes, String md5, String taskType) {
        String storagePath = saveToDisk(row.getDocId(), docType, bytes);
        LocalDateTime now = LocalDateTime.now();
        documentMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Document>()
                .eq("doc_id", row.getDocId())
                .set("status", "ACTIVE")
                .set("user_id", user)
                .set("filename", filename)
                .set("doc_type", docType)
                .set("storage_path", storagePath)
                .set("size_bytes", (long) bytes.length)
                .set("updated_at", now));
        // 复活后旧切片已被删除，这里再清一次以确保"重建语义"从干净状态开始
        chunkMapper.delete(new QueryWrapper<Chunk>().eq("doc_id", row.getDocId()));
        vectorStore.invalidate();

        Task task = taskService.createTask(taskType, row.getDocId(),
                JsonUtil.toJson(java.util.Map.of("docId", row.getDocId(), "docType", docType,
                        "filename", filename, "revived", true)));

        UploadResult result = new UploadResult();
        result.setDocId(row.getDocId());
        result.setFilename(filename);
        result.setDocType(docType);
        result.setMd5(md5);
        result.setSizeBytes(bytes.length);
        result.setDuplicate(false);
        result.setRevived(true);
        result.setTaskId(task.getTaskId());
        result.setTaskStatus(task.getStatus());
        result.setMessage("该内容此前已删除，本次导入已复活原文档并重建索引");

        taskPublisher.publish(task);
        log.info("复活导入已受理 docId={} taskId={}", row.getDocId(), task.getTaskId());
        return result;
    }

    @Override
    public UploadResult findDuplicateByMd5(String md5, String userId) {
        if (md5 == null || md5.isBlank()) {
            return null;
        }
        String user = normalizeUser(userId);
        Document existing = findByMd5(user, md5);
        if (existing == null || "DELETED".equals(existing.getStatus())) {
            return null;
        }
        return duplicateResult(existing, md5, "秒传命中：内容已存在（MD5 相同），无需上传分片");
    }

    @Override
    @Transactional
    public UploadResult registerMergedFile(String filename, String storagePath, String md5, long sizeBytes, String userId) {
        String user = normalizeUser(userId);
        String docType = TextExtractor.detectType(filename);

        // 与整体上传共用同一套幂等判定：不带 status 过滤地回查（唯一约束覆盖含已删除行）
        Document anyExisting = findByMd5(user, md5);
        if (anyExisting != null) {
            if (!"DELETED".equals(anyExisting.getStatus())) {
                log.info("分片合并后 MD5 幂等命中 user={} md5={} docId={}", user, md5, anyExisting.getDocId());
                return duplicateResult(anyExisting, md5, "合并后的内容与已有文档相同（MD5 幂等命中），已返回原文档");
            }
            // 复活：内容曾上传后被删除。storagePath 已由分片合并写好，这里只需更新行与元数据
            log.info("分片合并后命中已删除文档，执行复活导入 docId={}", anyExisting.getDocId());
            LocalDateTime now = LocalDateTime.now();
            documentMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Document>()
                    .eq("doc_id", anyExisting.getDocId())
                    .set("status", "ACTIVE")
                    .set("user_id", user)
                    .set("filename", filename)
                    .set("doc_type", docType)
                    .set("storage_path", storagePath)
                    .set("size_bytes", sizeBytes)
                    .set("updated_at", now));
            chunkMapper.delete(new QueryWrapper<Chunk>().eq("doc_id", anyExisting.getDocId()));
            vectorStore.invalidate();

            Task reviveTask = taskService.createTask(Task.TYPE_IMPORT_DOC, anyExisting.getDocId(),
                    JsonUtil.toJson(java.util.Map.of("docId", anyExisting.getDocId(), "docType", docType,
                            "filename", filename, "revived", true, "via", "chunked-upload")));
            UploadResult revived = new UploadResult();
            revived.setDocId(anyExisting.getDocId());
            revived.setFilename(filename);
            revived.setDocType(docType);
            revived.setMd5(md5);
            revived.setSizeBytes(sizeBytes);
            revived.setDuplicate(false);
            revived.setRevived(true);
            revived.setTaskId(reviveTask.getTaskId());
            revived.setTaskStatus(reviveTask.getStatus());
            revived.setMessage("该内容此前已删除，分片合并后已复活原文档并重建索引");
            taskPublisher.publish(reviveTask);
            return revived;
        }

        String docId = "d_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Document doc = new Document();
        doc.setDocId(docId);
        doc.setUserId(user);
        doc.setFilename(filename);
        doc.setDocType(docType);
        doc.setContentMd5(md5);
        doc.setStoragePath(storagePath);
        doc.setSizeBytes(sizeBytes);
        doc.setStatus("ACTIVE");
        LocalDateTime now = LocalDateTime.now();
        doc.setCreatedAt(now);
        doc.setUpdatedAt(now);
        try {
            documentMapper.insert(doc);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            Document raceWinner = findByMd5(user, md5);
            if (raceWinner == null) {
                throw new BusinessException(500, "文档入库失败（唯一约束冲突且无法回查）", e);
            }
            log.info("并发分片合并命中唯一约束，回退幂等 docId={}", raceWinner.getDocId());
            return duplicateResult(raceWinner, md5, "内容与已有文档相同（并发命唯一约束），已返回原文档");
        }

        Task task = taskService.createTask(Task.TYPE_IMPORT_DOC, docId,
                JsonUtil.toJson(java.util.Map.of("docId", docId, "docType", docType, "filename", filename,
                        "via", "chunked-upload")));

        UploadResult result = new UploadResult();
        result.setDocId(docId);
        result.setFilename(filename);
        result.setDocType(docType);
        result.setMd5(md5);
        result.setSizeBytes(sizeBytes);
        result.setDuplicate(false);
        result.setTaskId(task.getTaskId());
        result.setTaskStatus(task.getStatus());
        result.setMessage("分片合并完成，切分与向量化由后台消费者执行，可通过 GET /api/tasks/{taskId} 查询进度");
        taskPublisher.publish(task);
        return result;
    }

    /** 查任务当前状态（幂等命中时回带原任务状态，便于调用方一眼看出是否已完成）。 */
    private String statusOf(String taskId) {
        if (taskId == null) {
            return null;
        }
        Task t = taskService.getByTaskId(taskId);
        return t == null ? null : t.getStatus();
    }

    private String latestTaskId(String docId) {
        List<Task> tasks = taskService.listByDocId(docId);
        // listByDocId 已按创建时间倒序，取首条即最近一次任务
        return tasks.isEmpty() ? null : tasks.get(0).getTaskId();
    }

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }

    private static String md5Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            throw new BusinessException(500, "MD5 计算失败: " + e.getMessage(), e);
        }
    }

    private static String saveToDisk(String docId, String docType, byte[] bytes) {
        try {
            Path dir = Paths.get(STORAGE_DIR);
            Files.createDirectories(dir);
            Path target = dir.resolve(docId + "." + docType.toLowerCase());
            Files.write(target, bytes);
            return target.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new BusinessException(500, "原文落盘失败: " + e.getMessage(), e);
        }
    }
}
