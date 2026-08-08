package com.haohaop.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "RAG 查询请求")
public record QueryRequest(
        @NotBlank @Schema(description = "自然语言问题") String question,
        @Schema(description = "是否启用深度思考模式", defaultValue = "false") boolean deepThinking
) {
    public QueryRequest(String question) { this(question, false); }
}
