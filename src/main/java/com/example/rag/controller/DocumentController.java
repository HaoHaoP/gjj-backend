package com.example.rag.controller;

import com.example.rag.model.*;
import com.example.rag.service.DocumentService;
import com.example.rag.service.SyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

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
    public ResponseEntity<Map<String, Object>> listDocuments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(documentService.listDocuments(page, size, keyword));
    }

    @GetMapping("/{documentId}")
    @Operation(summary = "Get document detail", description = "Get document metadata by its UUID")
    public ResponseEntity<DocumentSummaryResponse> getDocument(@PathVariable String documentId) {
        return ResponseEntity.ok(documentService.getDocument(documentId));
    }

    @GetMapping("/{documentId}/chunks")
    @Operation(summary = "List document chunks", description = "List chunks for a specific document")
    public ResponseEntity<ChunkListResponse> listChunks(
            @PathVariable String documentId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(documentService.getDocumentChunks(documentId, page, size));
    }

    @GetMapping("/{documentId}/download")
    @Operation(summary = "Download original file", description = "Get a presigned URL for the original document file")
    public ResponseEntity<Map<String, String>> getDownloadUrl(@PathVariable String documentId) {
        String url = documentService.getDownloadUrl(documentId);
        return ResponseEntity.ok(Map.of("url", url));
    }

    // ========== Ingest ==========

    @PostMapping("/ingest")
    @Operation(summary = "Ingest document text", description = "Chunk, embed, and store document text")
    public ResponseEntity<Map<String, Object>> ingest(@Valid @RequestBody IngestRequest request) {
        Map<String, Object> result = documentService.ingest(
                request.title(), request.content(), "MANUAL",
                request.chunkSize(), request.overlapSize(), request.chunkMode());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @PostMapping("/upload")
    @Operation(summary = "Upload document file", description = "Upload a file to parse and ingest")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(defaultValue = "500") int chunkSize,
            @RequestParam(defaultValue = "0") int overlapSize,
            @RequestParam(defaultValue = "SENTENCE") String chunkMode) {
        try {
            Map<String, Object> result = documentService.ingestFromFile(
                    title, file.getInputStream(), file.getSize(),
                    file.getContentType(), file.getOriginalFilename(),
                    "UPLOAD", chunkSize, overlapSize, chunkMode);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to process uploaded file", e);
            return ResponseEntity.badRequest().body(Map.of("error", "无法解析文件: " + e.getMessage()));
        }
    }

    // ========== Delete ==========

    @DeleteMapping("/{documentId}")
    @Operation(summary = "Delete document", description = "Delete a document and all its chunks")
    public ResponseEntity<Void> deleteDocument(@PathVariable String documentId) {
        documentService.deleteDocument(documentId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{documentId}/chunks/{chunkId}")
    @Operation(summary = "Delete single chunk", description = "Delete a single chunk from a document")
    public ResponseEntity<Void> deleteChunk(@PathVariable String documentId, @PathVariable long chunkId) {
        documentService.deleteChunk(documentId, chunkId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/batch")
    @Operation(summary = "Batch delete documents", description = "Delete multiple documents by their UUIDs")
    public ResponseEntity<Map<String, Object>> deleteBatch(@RequestBody Map<String, java.util.List<String>> body) {
        java.util.List<String> ids = body.get("ids");
        int count = 0;
        for (String id : ids) {
            try { documentService.deleteDocument(id); count++; } catch (Exception ignored) {}
        }
        return ResponseEntity.ok(Map.of("deleted", count));
    }

    // ========== Sync ==========

    @PostMapping("/sync")
    @Operation(summary = "Sync documents", description = "Trigger crawl + extract pipeline with chunking params")
    public ResponseEntity<Map<String, String>> sync(
            @RequestParam(defaultValue = "500") int chunkSize,
            @RequestParam(defaultValue = "0") int overlapSize,
            @RequestParam(defaultValue = "SENTENCE") String chunkMode) {
        // Clear previous sync documents
        documentService.deleteBySource("SYNC");
        String taskId = syncService.startSync(chunkSize, overlapSize, chunkMode);
        return ResponseEntity.accepted().body(Map.of("taskId", taskId, "status", "running"));
    }

    @GetMapping("/sync/{taskId}")
    @Operation(summary = "Sync status", description = "Check sync task status")
    public ResponseEntity<Map<String, String>> syncStatus(@PathVariable String taskId) {
        return ResponseEntity.ok(Map.of("status", syncService.getStatus(taskId)));
    }
}
