package com.haohaop.rag.controller;

import com.haohaop.rag.model.ApiResponse;
import com.haohaop.rag.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chunks")
@Tag(name = "Chunks", description = "Chunk detail API")
public class ChunkController {

    private final DocumentService documentService;

    public ChunkController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get chunk detail", description = "Get single chunk metadata by its ID")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getChunk(@PathVariable long id) {
        var chunk = documentService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "id", chunk.id(),
            "title", chunk.title(),
            "chunkText", chunk.chunkText()
        )));
    }
}
