package com.haohaop.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "知识图谱交叉引用关系")
public record KgRelation(
        @Schema(description = "来源条款编号") String fromClause,
        @Schema(description = "关系类型：REFERENCES（引用）/REVISES（修订）/ABOLISHES（废止）") String relation,
        @Schema(description = "被引用文档标题") String toDocument
) {}
