package com.haohaop.rag.model;

import java.util.List;

public record ChunkListResponse(
    List<DocumentResponse> items,
    long total,
    int page,
    int size
) {}
