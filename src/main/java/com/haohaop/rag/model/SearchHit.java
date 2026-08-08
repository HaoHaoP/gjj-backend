package com.haohaop.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "向量相似度搜索结果")
public record SearchHit(
        @Schema(description = "分块 ID") long id,
        @Schema(description = "文档标题") String title,
        @Schema(description = "命中的分块文本") String chunkText,
        @Schema(description = "相似度得分") double score
) {}
