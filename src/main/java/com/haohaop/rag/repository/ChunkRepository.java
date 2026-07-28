package com.haohaop.rag.repository;

import com.haohaop.rag.entity.ChunkEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChunkRepository extends JpaRepository<ChunkEntity, Long> {
    Page<ChunkEntity> findByDocumentIdOrderByChunkIndexAsc(String documentId, Pageable pageable);
    List<ChunkEntity> findByDocumentIdOrderByChunkIndexAsc(String documentId);
    int countByDocumentId(String documentId);
    void deleteByDocumentId(String documentId);
}
