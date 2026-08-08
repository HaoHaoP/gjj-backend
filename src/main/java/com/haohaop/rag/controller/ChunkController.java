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
@Tag(name = "分块", description = "分块详情接口")
public class ChunkController {

    private final DocumentService documentService;

    public ChunkController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取分块详情", description = "根据分块 ID 获取单个分块元数据")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getChunk(@PathVariable long id) {
        var chunk = documentService.getById(id);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
            "id", chunk.id(),
            "title", chunk.title(),
            "chunkText", chunk.chunkText()
        )));
    }
}
