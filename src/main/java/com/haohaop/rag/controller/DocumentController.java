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
@Tag(name = "Documents", description = "Document management API")
public class DocumentController {

    private final DocumentService documentService;
    private final SyncService syncService;

    public DocumentController(DocumentService documentService, SyncService syncService) {
        this.documentService = documentService;
        this.syncService = syncService;
    }

    // ========== Document list & detail ==========

    @GetMapping
    @Operation(summary = "List documents", description = "List documents with pagination and optional keyword filter")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listDocuments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(ApiResponse.ok(documentService.listDocuments(page, size, keyword)));
    }

    @GetMapping("/{documentId}")
    @Operation(summary = "Get document detail", description = "Get document metadata by its UUID")
    public ResponseEntity<ApiResponse<DocumentSummaryResponse>> getDocument(@PathVariable String documentId) {
        return ResponseEntity.ok(ApiResponse.ok(documentService.getDocument(documentId)));
    }

    @GetMapping("/{documentId}/chunks")
    @Operation(summary = "List document chunks", description = "List chunks for a specific document")
    public ResponseEntity<ApiResponse<ChunkListResponse>> listChunks(
            @PathVariable String documentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(ApiResponse.ok(documentService.getDocumentChunks(documentId, page, size)));
    }

    @GetMapping("/{documentId}/download")
    @Operation(summary = "Download original file", description = "Get a presigned URL for the original document file")
    public ResponseEntity<ApiResponse<Map<String, String>>> getDownloadUrl(@PathVariable String documentId) {
        String url = documentService.getDownloadUrl(documentId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("url", url)));
    }

    // ========== Ingest ==========

    @PostMapping("/ingest")
    @Operation(summary = "Ingest document text", description = "Chunk, embed, and store document text. Optionally writes to MinIO if minioPath is provided.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> ingest(@Valid @RequestBody IngestRequest request) {
        Map<String, Object> result = documentService.ingest(
                request.title(), request.content(), request.source(),
                request.minioPath(), request.originalFilename(),
                request.chunkSize(), request.overlapSize(), request.chunkMode());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(result, "文档入库成功"));
    }

    @PostMapping("/upload")
    @Operation(summary = "Upload document file", description = "Upload a file to parse and ingest")
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
            // Sanitize null bytes
            text = text.replace("\u0000", "");
            Map<String, Object> result = documentService.ingestFromFileBytes(
                    title, bytes, text,
                    "UPLOAD", chunkSize, overlapSize, chunkMode, filename);
            return ResponseEntity.ok(ApiResponse.ok(result));
        } catch (Exception e) {
            log.error("Failed to process uploaded file", e);
            return ResponseEntity.badRequest().body(ApiResponse.fail(400, "无法解析文件: " + e.getMessage()));
        }
    }

    // ========== Delete ==========

    @DeleteMapping("/{documentId}")
    @Operation(summary = "Delete document", description = "Delete a document and all its chunks")
    public ResponseEntity<ApiResponse<Void>> deleteDocument(@PathVariable String documentId) {
        documentService.deleteDocument(documentId);
        return ResponseEntity.ok(ApiResponse.ok("文档已删除"));
    }

    @DeleteMapping("/{documentId}/chunks/{chunkId}")
    @Operation(summary = "Delete single chunk", description = "Delete a single chunk from a document")
    public ResponseEntity<ApiResponse<Void>> deleteChunk(@PathVariable String documentId, @PathVariable long chunkId) {
        documentService.deleteChunk(documentId, chunkId);
        return ResponseEntity.ok(ApiResponse.ok("分块已删除"));
    }

    @DeleteMapping("/batch")
    @Operation(summary = "Batch delete documents", description = "Delete multiple documents by their UUIDs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteBatch(@RequestBody Map<String, java.util.List<String>> body) {
        java.util.List<String> ids = body.get("ids");
        int count = documentService.deleteBatch(ids);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("deleted", count)));
    }

    // ========== Sync ==========

    @PostMapping("/sync")
    @Operation(summary = "Sync documents", description = "Trigger crawl + extract pipeline with chunking params")
    public ResponseEntity<ApiResponse<Map<String, String>>> sync() {
        String taskId = syncService.startSync();
        return ResponseEntity.accepted().body(ApiResponse.ok(Map.of("taskId", taskId, "status", "running"), "同步任务已创建"));
    }

    @GetMapping("/sync/{taskId}")
    @Operation(summary = "Sync status", description = "Check sync task status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> syncStatus(@PathVariable String taskId) {
        SyncService.SyncTask t = syncService.getStatus(taskId);
        if (t == null) return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "not_found")));
        return ResponseEntity.ok(ApiResponse.ok(Map.of("status", t.status, "progress", t.progress, "stage", t.stage != null ? t.stage : "", "error", t.error != null ? t.error : "")));
    }
}
