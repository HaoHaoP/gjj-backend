package com.example.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "RAG query request")
public record QueryRequest(
        @NotBlank @Schema(description = "Natural language question") String question
) {}
