package com.aiteaching.kb.service;

import com.aiteaching.kb.service.dto.UploadInitRequest;
import com.aiteaching.kb.service.dto.UploadInitResult;
import com.aiteaching.kb.service.dto.UploadProgress;
import com.aiteaching.kb.service.dto.UploadResult;

/**
 * 分片上传（断点续传）服务。
 *
 * <p><b>与 {@link DocService#upload} 的分工</b>：整体上传适合小文件（一次 multipart 到位）；
 * 分片上传适合大文件/弱网（客户端可中断后只补缺失分片）。两者最终都收敛到同一条
 * "落盘原文 → 建文档行 → 建 IMPORT_DOC 任务 → 投递 MQ"的流水线，因此
 * {@code processImport} 及下游的切分/向量化/RAG 链路<b>完全不需要改动</b>。</p>
 */
public interface UploadService {

    /**
     * 初始化上传会话：分配 uploadId、切分片数、写会话行，并按 fileMd5 做秒传预判。
     *
     * <p>若文件类型不支持（非 MD/TXT/DOCX）直接拒绝——把校验提前到 init 阶段，
     * 避免客户端白传几个 GB 才在合并时被拒。</p>
     */
    UploadInitResult init(UploadInitRequest request);

    /**
     * 接收一个分片。同一 seq 重复提交 = 覆盖（幂等），客户端重试无害。
     *
     * @param chunkMd5 客户端算的分片 MD5；非空时服务端校验，防"网络截断的坏分片"入库
     */
    UploadProgress receiveChunk(String uploadId, int seq, byte[] bytes, String chunkMd5, String userId);

    /** 查询进度：返回已收/缺失分片序号，客户端据此只补缺失部分（断点续传的关键接口）。 */
    UploadProgress progress(String uploadId, String userId);

    /**
     * 合并全部分片并接入导入流水线：校验分片齐备 → 合并（含整文件 MD5 强校验）→
     * 建文档行 → 建 IMPORT_DOC 任务 → 提交后投递 MQ。
     *
     * <p>幂等：已 MERGED 的会话重复调用直接返回原 docId/taskId，不会重复建文档。</p>
     */
    UploadResult merge(String uploadId, String userId);

    /** 主动放弃上传：清理分片与临时状态，会话置 ABORTED。 */
    void abort(String uploadId, String userId);

    /**
     * 回收过期会话：清理分片目录、会话置 EXPIRED（由定时任务调用）。
     *
     * @return 实际回收的会话数
     */
    int reapExpired();
}
