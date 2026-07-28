package com.example.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Document ingestion request")
public record IngestRequest(
    @NotBlank @Schema(description = "Document title") String title,
    @NotBlank @Schema(description = "Document content") String content,
    @Schema(description = "Chunk size in characters", defaultValue = "500") int chunkSize,
    @Schema(description = "Overlap size in characters", defaultValue = "0") int overlapSize,
    @Schema(description = "Chunk mode: SENTENCE or FIXED", defaultValue = "SENTENCE") String chunkMode
) {
    public IngestRequest {
        if (chunkSize <= 0) chunkSize = 500;
        if (overlapSize < 0) overlapSize = 0;
        if (chunkMode == null || chunkMode.isBlank()) chunkMode = "SENTENCE";
    }
}
