package com.example.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Knowledge graph cross-reference relation")
public record KgRelation(
        @Schema(description = "Source clause number") String fromClause,
        @Schema(description = "Relation type: REFERENCES/REVISES/ABOLISHES") String relation,
        @Schema(description = "Referenced document title") String toDocument
) {}
