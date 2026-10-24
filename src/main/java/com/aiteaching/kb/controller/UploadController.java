package com.aiteaching.kb.controller;

import com.aiteaching.kb.common.ApiResponse;
import com.aiteaching.kb.service.UploadService;
import com.aiteaching.kb.service.dto.UploadInitRequest;
import com.aiteaching.kb.service.dto.UploadInitResult;
import com.aiteaching.kb.service.dto.UploadProgress;
import com.aiteaching.kb.service.dto.UploadResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 分片上传接口（断点续传）。
 *
 * <p>协议：init（分配 uploadId + 秒传预判）→ 循环 PUT 分片 → merge（合并并接入导入流水线）。
 * 任意时刻可 GET /progress 拿到"已收/缺失分片"，客户端只补缺失部分。</p>
 */
@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    /** 1) 初始化：返回 uploadId、chunkSize、totalChunks；命中秒传时 instant=true 且无需上传。 */
    @PostMapping("/init")
    public ApiResponse<UploadInitResult> init(@RequestBody UploadInitRequest request) {
        return ApiResponse.ok(uploadService.init(request));
    }

    /**
     * 2) 上传单个分片（multipart，字段名 chunk）。
     *
     * <p>同一 seq 重复提交 = 覆盖（幂等），客户端重试无害。返回最新进度，
     * 客户端可据此判断还需补哪些片。</p>
     */
    @PutMapping("/{uploadId}/chunks/{seq}")
    public ApiResponse<UploadProgress> uploadChunk(@PathVariable String uploadId,
                                                   @PathVariable int seq,
                                                   @RequestParam("chunk") MultipartFile chunk,
                                                   @RequestParam(value = "chunkMd5", required = false) String chunkMd5,
                                                   @RequestParam(value = "userId", required = false) String userId) {
        byte[] bytes;
        try {
            bytes = chunk.getBytes();
        } catch (IOException e) {
            throw new com.aiteaching.kb.common.BusinessException(500, "读取分片失败: " + e.getMessage(), e);
        }
        return ApiResponse.ok(uploadService.receiveChunk(uploadId, seq, bytes, chunkMd5, userId));
    }

    /** 3) 查询进度：已收/缺失分片序号、百分比——断点续传的核心接口。 */
    @GetMapping("/{uploadId}/progress")
    public ApiResponse<UploadProgress> progress(@PathVariable String uploadId,
                                                @RequestParam(value = "userId", required = false) String userId) {
        return ApiResponse.ok(uploadService.progress(uploadId, userId));
    }

    /** 4) 合并：校验齐备 → 合并（整文件 MD5 强校验）→ 落库 → 投递导入任务。幂等。 */
    @PostMapping("/{uploadId}/merge")
    public ApiResponse<UploadResult> merge(@PathVariable String uploadId,
                                           @RequestParam(value = "userId", required = false) String userId) {
        return ApiResponse.ok(uploadService.merge(uploadId, userId));
    }

    /** 5) 放弃上传：清理分片与元数据。 */
    @DeleteMapping("/{uploadId}")
    public ApiResponse<Map<String, Object>> abort(@PathVariable String uploadId,
                                                  @RequestParam(value = "userId", required = false) String userId) {
        uploadService.abort(uploadId, userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("uploadId", uploadId);
        data.put("aborted", true);
        return ApiResponse.ok(data);
    }

    /**
     * 运维口：手动触发过期会话回收（正常情况下由定时任务自动执行）。
     */
    @PostMapping("/reap")
    public ApiResponse<Map<String, Object>> reap() {
        int n = uploadService.reapExpired();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reaped", n);
        return ApiResponse.ok(data);
    }
}
