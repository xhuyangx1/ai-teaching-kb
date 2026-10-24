package com.aiteaching.kb.service;

import com.aiteaching.kb.entity.Chunk;
import com.aiteaching.kb.entity.Document;
import com.aiteaching.kb.entity.Task;
import com.aiteaching.kb.service.dto.ChunkView;
import com.aiteaching.kb.service.dto.UploadResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档服务：上传（MD5 幂等）→ 落盘 → 建 PENDING 任务 → 投递 MQ；导入线程里做切分/向量化/入库。
 */
public interface DocService {

    /** 上传文档：同 user 同 MD5 命中则直接返回原 docId（duplicate=true）。 */
    UploadResult upload(MultipartFile file, String userId);

    /**
     * 聊天记录导入入口：把已抽取的文本作为 CHAT 文档落库并投递同一条 MQ 流水线。
     *
     * <p><b>交接说明（给聊天导入模块）</b>：本方法只负责"文档行 + IMPORT_CHAT 任务 + 投递"，
     * 产生的是 {@link Task#TYPE_IMPORT_CHAT} 任务。当前 {@code DocImportTaskHandler} 只声明
     * {@link Task#TYPE_IMPORT_DOC}，因此<b>在聊天导入模块落地之前，IMPORT_CHAT 任务会被消费者
     * 判定为"无匹配处理器"并置 FAILED</b>。接入方式：新增一个 {@code ChatImportTaskHandler}
     * 实现 {@link com.aiteaching.kb.mq.TaskHandler}，{@code supports()} 认 TYPE_IMPORT_CHAT，
     * 内部用 {@code ChatSplitter} 切分（保留 speaker/msg_time）后调用
     * {@link #persistChunks(String, List, int)} 完成向量化入库即可，消费者与状态机无需改动。</p>
     */
    UploadResult importChatDocument(String filename, String content, String userId);

    /**
     * 登记一个"已经落盘"的文件为文档并接入导入流水线（分片上传合并后调用）。
     *
     * <p>与 {@link #upload} 的区别：{@code upload} 接收 multipart 字节、自己落盘；
     * 本方法接收的路径<b>已经存在</b>（分片合并产物），只做"MD5 幂等判定 → 建文档行 →
     * 建 IMPORT_DOC 任务 → 提交后投递"。两者下游完全一致，因此切分/向量化/RAG 无需改动。</p>
     *
     * @param filename    原始文件名（决定 docType 与展示名）
     * @param storagePath 已经落盘的原文绝对/相对路径
     * @param md5         合并时算出的整文件 MD5（用于幂等）
     * @param sizeBytes   文件字节数
     */
    /**
     * 按 MD5 查是否已有同内容文档（用于分片上传 init 阶段的秒传预判）。
     *
     * @return 命中且仍为 ACTIVE 时返回幂等结果；未命中返回 {@code null}
     */
    UploadResult findDuplicateByMd5(String md5, String userId);

    UploadResult registerMergedFile(String filename, String storagePath, String md5, long sizeBytes, String userId);

    /** 逻辑删除文档并物理删除其全部 chunks（向量重建入口）。 */
    void logicalDelete(String docId);

    List<ChunkView> listChunks(String docId);

    Document getByDocId(String docId);

    List<Document> listActive();

    /** 导入任务的实际处理逻辑（由 TaskHandler 调用，运行在消费线程）。 */
    void processImport(Task task);

    /** 写入切片（切分+向量化），供文档与聊天两条切分路径共用。 */
    int persistChunks(String docId, List<com.aiteaching.kb.rag.splitter.SplitChunk> splitChunks, int progressFrom);
}
