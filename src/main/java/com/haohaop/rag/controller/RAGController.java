package com.haohaop.rag.controller;

import com.haohaop.rag.model.ApiResponse;
import com.haohaop.rag.model.QueryRequest;
import com.haohaop.rag.model.QueryResponse;
import com.haohaop.rag.service.RAGService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RestController
@RequestMapping("/api/rag")
@Tag(name = "RAG", description = "RAG Q&A API")
public class RagController {

    private final RAGService ragService;

    public RagController(RAGService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/query")
    @Operation(summary = "Ask a policy question")
    public ResponseEntity<ApiResponse<QueryResponse>> query(@Valid @RequestBody QueryRequest request) {
        QueryResponse result = ragService.query(request.question());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Ask a policy question with streaming response")
    public SseEmitter queryStream(@Valid @RequestBody QueryRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);
        new Thread(() -> ragService.askStream(request.question(), request.deepThinking(), emitter)).start();
        return emitter;
    }
}
