package com.example.rag.controller;

import com.example.rag.model.DocumentResponse;
import com.example.rag.model.IngestRequest;
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
import java.io.IOException;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/documents")
@Tag(name = "Documents", description = "Document ingestion and management API")
public class DocumentController {

    private final DocumentService documentService;
    private final SyncService syncService;

    public DocumentController(DocumentService documentService, SyncService syncService) {
        this.documentService = documentService;
        this.syncService = syncService;
    }

    @PostMapping("/ingest")
    @Operation(summary = "Ingest a document", description = "Split, embed, and store a document in Milvus")
    public ResponseEntity<Map<String, Object>> ingest(@Valid @RequestBody IngestRequest request) {
        int chunkCount = documentService.ingest(request.title(), request.content());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                        "message", "Document ingested successfully",
                        "chunks", chunkCount,
                        "title", request.title()
                ));
    }

    @GetMapping
    @Operation(summary = "List document chunks with pagination", description = "Retrieve chunks with pagination and optional keyword filter")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        List<Long> allIds = documentService.getAllIds();

        // Filter by keyword if provided
        if (keyword != null && !keyword.isBlank()) {
            allIds = allIds.stream()
                    .map(documentService::getById)
                    .filter(d -> d != null)
                    .filter(d -> d.title().contains(keyword) || d.chunkText().contains(keyword))
                    .map(com.example.rag.model.DocumentResponse::id)
                    .toList();
        }

        int total = allIds.size();
        int from = (page - 1) * size;
        int to = Math.min(from + size, total);
        List<Long> pageIds = allIds.subList(Math.min(from, total), to);
        List<DocumentResponse> items = documentService.getByIds(pageIds);

        return ResponseEntity.ok(Map.of("items", items, "total", total, "page", page, "size", size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get document chunk by ID", description = "Retrieve a specific chunk by its ID")
    public ResponseEntity<DocumentResponse> getById(@PathVariable long id) {
        DocumentResponse doc = documentService.getById(id);
        if (doc == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(doc);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete document chunk", description = "Delete a chunk by its ID")
    public ResponseEntity<Void> deleteById(@PathVariable long id) {
        documentService.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/batch")
    @Operation(summary = "Batch delete document chunks", description = "Delete multiple chunks by their IDs")
    public ResponseEntity<Map<String, Object>> deleteBatch(@RequestBody Map<String, List<Long>> body) {
        List<Long> ids = body.get("ids");
        int count = documentService.deleteBatch(ids);
        return ResponseEntity.ok(Map.of("deleted", count));
    }


    @PostMapping("/sync")
    @Operation(summary = "Sync documents", description = "Trigger crawl + extract pipeline")
    public ResponseEntity<Map<String, String>> sync() {
        String taskId = syncService.startSync();
        return ResponseEntity.accepted().body(Map.of("taskId", taskId, "status", "running"));
    }

    @GetMapping("/sync/{taskId}")
    @Operation(summary = "Sync status", description = "Check sync task status")
    public ResponseEntity<Map<String, String>> syncStatus(@PathVariable String taskId) {
        return ResponseEntity.ok(Map.of("status", syncService.getStatus(taskId)));
    }

    @PostMapping("/upload")
    @Operation(summary = "Upload document file", description = "Upload a file to ingest")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title) {
        try {
            String text = new org.apache.tika.Tika().parseToString(file.getInputStream());
            int count = documentService.ingest(title, text);
            return ResponseEntity.ok(Map.of("ingested", count, "title", title));
        } catch (Exception e) {
            log.error("Failed to parse uploaded file", e);
            return ResponseEntity.badRequest().body(Map.of("error", "无法解析文件: " + e.getMessage()));
        }
    }

}
