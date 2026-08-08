package com.haohaop.rag.controller;

import com.haohaop.rag.model.*;
import com.haohaop.rag.service.DocumentService;
import com.haohaop.rag.service.SyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import com.haohaop.rag.model.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@Tag(name = "文档", description = "文档管理接口")
public class DocumentController {

    private final DocumentService documentService;
    private final SyncService syncService;

    public DocumentController(DocumentService documentService, SyncService syncService) {
        this.documentService = documentService;
        this.syncService = syncService;
    }

    // ========== 文档列表与详情 ==========

    @GetMapping
    @Operation(summary = "文档列表", description = "分页查询文档列表，支持可选的关键字过滤")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listDocuments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(ApiResponse.ok(documentService.listDocuments(page, size, keyword)));
    }

    @GetMapping("/{documentId}")
    @Operation(summary = "获取文档详情", description = "根据文档 UUID 获取文档元数据")
    public ResponseEntity<ApiResponse<DocumentSummaryResponse>> getDocument(@PathVariable String documentId) {
        return ResponseEntity.ok(ApiResponse.ok(documentService.getDocument(documentId)));
    }

    @GetMapping("/{documentId}/chunks")
    @Operation(summary = "文档分块列表", description = "获取指定文档的分块列表")
    public ResponseEntity<ApiResponse<ChunkListResponse>> listChunks(
            @PathVariable String documentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(documentService.getDocumentChunks(documentId, page, size)));
    }

    @GetMapping("/{documentId}/download")
    @Operation(summary = "下载原始文件", description = "获取原始文档文件的预签名下载地址")
    public ResponseEntity<ApiResponse<Map<String, String>>> getDownloadUrl(@PathVariable String documentId) {
        String url = documentService.getDownloadUrl(documentId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("url", url)));
    }

    // ========== 入库 ==========

    @PostMapping("/ingest")
    @Operation(summary = "文档文本入库", description = "对文档文本进行分块、向量化并入库。若提供了 minioPath，还会写入 MinIO。")
    public ResponseEntity<ApiResponse<Map<String, Object>>> ingest(@Valid @RequestBody IngestRequest request) {
        Map<String, Object> result = documentService.ingest(
                request.title(), request.content(), request.source(),
                request.minioPath(), request.originalFilename(),
                request.chunkSize(), request.overlapSize(), request.chunkMode());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(result, "文档入库成功"));
    }

    @PostMapping("/upload")
    @Operation(summary = "上传文档文件", description = "上传文件并解析入库")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(defaultValue = "500") int chunkSize,
            @RequestParam(defaultValue = "0") int overlapSize,
            @RequestParam(defaultValue = "SENTENCE") String chunkMode) {
        try {
            byte[] bytes = file.getBytes();
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
            String text;
            if (filename.endsWith(".txt")) {
                text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            } else {
                text = new org.apache.tika.Tika().parseToString(
                        new java.io.ByteArrayInputStream(bytes));
            }
            // 清理空字节
            text = text.replace("\u0000", "");
            Map<String, Object> result = documentService.ingestFromFileBytes(
                    title, bytes, text,
                    "UPLOAD", chunkSize, overlapSize, chunkMode, filename);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            log.error("处理上传文件失败", e);
            return ResponseEntity.badRequest().body(ApiResponse.fail(400, "无法解析文件: " + e.getMessage()));
        }
    }

    // ========== 删除 ==========

    @DeleteMapping("/{documentId}")
    @Operation(summary = "删除文档", description = "删除文档及其全部分块")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(@PathVariable String documentId) {
        documentService.deleteDocument(documentId);
        return ResponseEntity.ok(ApiResponse.ok("文档已删除"));
    }

    @DeleteMapping("/{documentId}/chunks/{chunkId}")
    @Operation(summary = "删除单个分块", description = "删除文档中的单个分块")
    public ResponseEntity<ApiResponse<Void>> deleteChunk(@PathVariable String documentId, @PathVariable long chunkId) {
        documentService.deleteChunk(documentId, chunkId);
        return ResponseEntity.ok(ApiResponse.ok("分块已删除"));
    }

    @DeleteMapping("/batch")
    @Operation(summary = "批量删除文档", description = "根据文档 UUID 批量删除文档")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteBatch(@RequestBody Map<String, java.util.List<String>> body) {
        java.util.List<String> ids = body.get("ids");
        int count = documentService.deleteBatch(ids);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("deleted", count)));
    }

    // ========== 同步 ==========

    @PostMapping("/sync")
    @Operation(summary = "同步文档", description = "触发抓取与解析流水线，并携带分块参数")
    public ResponseEntity<ApiResponse<Map<String, String>>> sync() {
        String taskId = syncService.startSync();
        return ResponseEntity.accepted().body(ApiResponse.ok(Map.of("taskId", taskId, "status", "running"), "同步任务已创建"));
    }

    @GetMapping("/sync/{taskId}")
    @Operation(summary = "同步状态", description = "查询同步任务状态")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncStatus(@PathVariable String taskId) {
        SyncService.SyncTask t = syncService.getStatus(taskId);
        if (t == null) return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "not_found")));
        return ResponseEntity.ok(ApiResponse.ok(Map.of("status", t.status, "progress", t.progress, "stage", t.stage != null ? t.stage : "", "error", t.error != null ? t.error : "")));
    }
}
