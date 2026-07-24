package com.example.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Document ingestion request")
public record IngestRequest(
        @NotBlank @Schema(description = "Document title") String title,
        @NotBlank @Schema(description = "Document content") String content
) {}
