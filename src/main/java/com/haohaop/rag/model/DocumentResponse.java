package com.haohaop.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "文档分块响应")
public record DocumentResponse(
        @Schema(description = "分块 ID") long id,
        @Schema(description = "文档标题") String title,
        @Schema(description = "分块文本内容") String chunkText,
        @Schema(description = "条款编号（如：第一条）") String clauseNumber
) {
    // 保留旧构造函数以兼容旧代码
    public DocumentResponse(long id, String title, String chunkText) {
        this(id, title, chunkText, null);
    }
}
