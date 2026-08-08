package com.haohaop.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "来源文档匹配项")
public record SourceInfo(
        @Schema(description = "分块 ID") long id,
        @Schema(description = "文档标题") String title,
        @Schema(description = "分块文本内容") String chunkText,
        @Schema(description = "相似度得分") double score
) {}
