package com.haohaop.rag.model;

import com.haohaop.rag.entity.DocumentEntity;

import java.time.LocalDateTime;

public record DocumentSummaryResponse(
    String documentId,
    String title,
    String source,
    int chunkCount,
    int chunkSize,
    int overlapSize,
    String chunkMode,
    String minioPath,
    String originalFilename,
    long fileSize,
    String status,
    LocalDateTime createdAt
) {
    public static DocumentSummaryResponse from(DocumentEntity e) {
        return new DocumentSummaryResponse(
            e.getDocumentId(), e.getTitle(), e.getSource(),
            e.getChunkCount(), e.getChunkSize(), e.getOverlapSize(), e.getChunkMode(),
            e.getMinioPath(), e.getOriginalFilename(), e.getFileSize(), e.getStatus(),
            e.getCreatedAt()
        );
    }
}
