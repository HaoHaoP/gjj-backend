package com.haohaop.rag.controller;

import com.haohaop.rag.model.FeedbackRequest;
import com.haohaop.rag.model.QueryRequest;
import com.haohaop.rag.model.QueryResponse;
import com.haohaop.rag.service.RAGService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import com.haohaop.rag.model.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/rag")
@Tag(name = "RAG Query", description = "RAG question-answering API")
public class RAGController {

    private final RAGService ragService;

    public RAGController(RAGService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/query")
    @Operation(summary = "Ask a question", description = "Query the RAG system with a natural language question")
    public ResponseEntity<ApiResponse<QueryResponse>> query(@Valid @RequestBody QueryRequest request) {
        QueryResponse response = ragService.query(request.question());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @PostMapping("/feedback")
    @Operation(summary = "Submit feedback", description = "Submit user feedback on answer")
    public ResponseEntity<ApiResponse<Void>> feedback(@RequestBody FeedbackRequest req) {
        log.info("Feedback: rating={}, question={}", req.rating(),
                req.question().substring(0, Math.min(50, req.question().length())));
        return ResponseEntity.ok(ApiResponse.ok("反馈已提交"));
    }
}
