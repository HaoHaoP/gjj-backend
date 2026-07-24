package com.example.rag.controller;

import com.example.rag.model.FeedbackRequest;
import com.example.rag.model.QueryRequest;
import com.example.rag.model.QueryResponse;
import com.example.rag.service.RAGService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
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
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest request) {
        QueryResponse response = ragService.query(request.question());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/feedback")
    @Operation(summary = "Submit feedback", description = "Submit user feedback on answer")
    public ResponseEntity<Void> feedback(@RequestBody FeedbackRequest req) {
        log.info("Feedback: rating={}, question={}", req.rating(),
                req.question().substring(0, Math.min(50, req.question().length())));
        return ResponseEntity.ok().build();
    }
}
