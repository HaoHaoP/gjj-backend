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
@Table(name = "chunks")
public class ChunkEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(name = "clause_number", length = 50)
    private String clauseNumber;

    @Column(name = "parent_title", length = 500)
    private String parentTitle;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public ChunkEntity(String documentId, int chunkIndex, String text, String clauseNumber, String parentTitle) {
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.text = text;
        this.clauseNumber = clauseNumber;
        this.parentTitle = parentTitle;
        this.createdAt = LocalDateTime.now();
    }
}
