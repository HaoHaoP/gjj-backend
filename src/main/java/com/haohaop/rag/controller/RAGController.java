package com.haohaop.rag.controller;

import com.haohaop.rag.model.ApiResponse;
import com.haohaop.rag.service.FeedbackService;
import com.haohaop.rag.model.FeedbackRequest;
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
@Tag(name = "RAG", description = "RAG 问答接口")
public class RagController {

    private final RAGService ragService;
    private final FeedbackService feedbackService;

    public RagController(RAGService ragService, FeedbackService feedbackService) {
        this.ragService = ragService;
        this.feedbackService = feedbackService;
    }

    @PostMapping("/query")
    @Operation(summary = "政策问答")
    public ResponseEntity<ApiResponse<QueryResponse>> query(@Valid @RequestBody QueryRequest request) {
        QueryResponse result = ragService.query(request.question());
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @PostMapping("/feedback")
    @Operation(summary = "提交反馈")
    public ResponseEntity<ApiResponse<Void>> feedback(@RequestBody FeedbackRequest request) {
        feedbackService.save(request.question(), request.answer(), request.rating());
        return ResponseEntity.ok(ApiResponse.ok("反馈已记录"));
    }

    @PostMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式政策问答")
    public SseEmitter queryStream(@Valid @RequestBody QueryRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);
        new Thread(() -> ragService.askStream(request.question(), request.deepThinking(), emitter)).start();
        return emitter;
    }
}
