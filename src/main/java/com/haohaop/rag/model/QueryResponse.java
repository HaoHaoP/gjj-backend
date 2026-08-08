package com.haohaop.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "RAG 查询响应")
public record QueryResponse(
        @Schema(description = "生成的回答") String answer,
        @Schema(description = "使用的来源文档") List<SourceInfo> sources,
        @Schema(description = "问题是否被拒答（陷阱题）") boolean rejected,
        @Schema(description = "知识图谱引用关系") List<KgRelation> kgRelations
) {
    // 保留旧构造函数以兼容旧代码
    public QueryResponse(String answer, List<SourceInfo> sources) {
        this(answer, sources, false, List.of());
    }
}
