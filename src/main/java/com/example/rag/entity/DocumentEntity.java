package com.example.rag.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "documents")
public class DocumentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false, unique = true, length = 36)
    private String documentId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, length = 20)
    private String source; // SYNC, UPLOAD, MANUAL

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "chunk_size", nullable = false)
    private int chunkSize;

    @Column(name = "overlap_size", nullable = false)
    private int overlapSize;

    @Column(name = "chunk_mode", nullable = false, length = 20)
    private String chunkMode;

    @Column(name = "minio_path", length = 500)
    private String minioPath;

    @Column(name = "sync_batch_id", length = 36)
    private String syncBatchId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public DocumentEntity() {}

    public DocumentEntity(String documentId, String title, String source, int chunkCount,
                          int chunkSize, int overlapSize, String chunkMode) {
        this.documentId = documentId;
        this.title = title;
        this.source = source;
        this.chunkCount = chunkCount;
        this.chunkSize = chunkSize;
        this.overlapSize = overlapSize;
        this.chunkMode = chunkMode;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and setters
    public Long getId() { return id; }
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public int getChunkCount() { return chunkCount; }
    public void setChunkCount(int chunkCount) { this.chunkCount = chunkCount; }
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
    public int getOverlapSize() { return overlapSize; }
    public void setOverlapSize(int overlapSize) { this.overlapSize = overlapSize; }
    public String getChunkMode() { return chunkMode; }
    public void setChunkMode(String chunkMode) { this.chunkMode = chunkMode; }
    public String getMinioPath() { return minioPath; }
    public void setMinioPath(String minioPath) { this.minioPath = minioPath; }
    public String getSyncBatchId() { return syncBatchId; }
    public void setSyncBatchId(String syncBatchId) { this.syncBatchId = syncBatchId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
