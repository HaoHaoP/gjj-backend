package com.example.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "RAG query response")
public record QueryResponse(
        @Schema(description = "Generated answer") String answer,
        @Schema(description = "Source documents used") List<SourceInfo> sources,
        @Schema(description = "Whether the question was rejected (trap)") boolean rejected,
        @Schema(description = "Knowledge graph relations") List<KgRelation> kgRelations
) {
    // Legacy constructor for backward compatibility
    public QueryResponse(String answer, List<SourceInfo> sources) {
        this(answer, sources, false, List.of());
    }
}
