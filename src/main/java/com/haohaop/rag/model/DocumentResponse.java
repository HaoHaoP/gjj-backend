package com.haohaop.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Document chunk response")
public record DocumentResponse(
        @Schema(description = "Chunk ID") long id,
        @Schema(description = "Document title") String title,
        @Schema(description = "Chunk text content") String chunkText
) {}
