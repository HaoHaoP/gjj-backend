package com.haohaop.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Search result from vector similarity search")
public record SearchHit(
        @Schema(description = "Chunk ID") long id,
        @Schema(description = "Document title") String title,
        @Schema(description = "Matching chunk text") String chunkText,
        @Schema(description = "Similarity score") double score
) {}
