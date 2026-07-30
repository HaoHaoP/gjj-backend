package com.haohaop.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "RAG query request")
public record QueryRequest(
        @NotBlank @Schema(description = "Natural language question") String question,
        @Schema(description = "Enable deep thinking mode", defaultValue = "false") boolean deepThinking
) {
    public QueryRequest(String question) { this(question, false); }
}
