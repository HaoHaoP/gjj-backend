package com.haohaop.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Source document match")
public record SourceInfo(
        @Schema(description = "Chunk ID") long id,
        @Schema(description = "Document title") String title,
        @Schema(description = "Chunk text content") String chunkText,
        @Schema(description = "Similarity score") double score
) {}
