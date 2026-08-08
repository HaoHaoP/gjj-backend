package com.haohaop.rag.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "文档入库请求")
public record IngestRequest(
    @NotBlank @Schema(description = "文档标题") String title,
    @NotBlank @Schema(description = "文档内容") String content,
    @Schema(description = "MinIO 对象路径；若设置，入库成功后内容会写入 MinIO") String minioPath,
    @Schema(description = "用于下载的原始文件名") String originalFilename,
    @Schema(description = "文档来源：MANUAL（手动）、UPLOAD（上传）或 SYNC（同步）", defaultValue = "MANUAL") String source,
    @Schema(description = "分块大小（字符数）", defaultValue = "500") int chunkSize,
    @Schema(description = "重叠大小（字符数）", defaultValue = "0") int overlapSize,
    @Schema(description = "分块模式：SENTENCE（按句）或 FIXED（固定长度）", defaultValue = "SENTENCE") String chunkMode
) {
    public IngestRequest {
        if (chunkSize <= 0) chunkSize = 500;
        if (overlapSize < 0) overlapSize = 0;
        if (chunkMode == null || chunkMode.isBlank()) chunkMode = "SENTENCE";
        if (source == null || source.isBlank()) source = "MANUAL";
    }
}
