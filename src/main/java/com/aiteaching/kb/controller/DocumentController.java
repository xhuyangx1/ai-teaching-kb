package com.aiteaching.kb.controller;

import com.aiteaching.kb.common.ApiResponse;
import com.aiteaching.kb.service.DocService;
import com.aiteaching.kb.service.dto.ChunkView;
import com.aiteaching.kb.service.dto.UploadResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档接口：上传（MD/TXT/DOCX，MD5 幂等）、逻辑删除、切片查询。
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocService docService;

    public DocumentController(DocService docService) {
        this.docService = docService;
    }

    /** 上传文档：multipart，字段名 file；立即返回 {taskId, docId}，导入异步执行。 */
    @PostMapping
    public ApiResponse<UploadResult> upload(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "userId", required = false) String userId) {
        return ApiResponse.ok(docService.upload(file, userId));
    }

    /** 逻辑删除文档并删除其全部切片。 */
    @DeleteMapping("/{docId}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String docId,
                                                   @RequestParam(value = "userId", required = false) String userId) {
        docService.logicalDelete(docId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docId", docId);
        data.put("deleted", true);
        return ApiResponse.ok(data);
    }

    /** 查询文档切片（不含向量，含 speaker/msgTime）。 */
    @GetMapping("/{docId}/chunks")
    public ApiResponse<Map<String, Object>> chunks(@PathVariable String docId) {
        List<ChunkView> chunks = docService.listChunks(docId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("docId", docId);
        data.put("count", chunks.size());
        data.put("chunks", chunks);
        return ApiResponse.ok(data);
    }

    /** 列出当前有效文档（调试/演示用）。 */
    @GetMapping
    public ApiResponse<Map<String, Object>> list() {
        List<com.aiteaching.kb.entity.Document> docs = docService.listActive();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", docs.size());
        data.put("documents", docs);
        return ApiResponse.ok(data);
    }
}
