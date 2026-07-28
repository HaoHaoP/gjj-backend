package com.example.rag.model;

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
    LocalDateTime createdAt
) {}
