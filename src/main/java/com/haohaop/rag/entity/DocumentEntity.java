package com.haohaop.rag.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
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
    private String source;

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

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "file_size")
    private long fileSize;

    @Column(length = 20)
    private String status;

    @Column(name = "sync_batch_id", length = 36)
    private String syncBatchId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public DocumentEntity(String documentId, String title, String source, int chunkCount,
                          int chunkSize, int overlapSize, String chunkMode,
                          String minioPath, String originalFilename, long fileSize) {
        this.documentId = documentId;
        this.title = title;
        this.source = source;
        this.chunkCount = chunkCount;
        this.chunkSize = chunkSize;
        this.overlapSize = overlapSize;
        this.chunkMode = chunkMode;
        this.minioPath = minioPath;
        this.originalFilename = originalFilename;
        this.fileSize = fileSize;
        this.status = "active";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
}
