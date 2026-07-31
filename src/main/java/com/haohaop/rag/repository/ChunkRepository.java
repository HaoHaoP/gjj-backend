package com.haohaop.rag.repository;

import com.haohaop.rag.entity.ChunkEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ChunkRepository extends JpaRepository<ChunkEntity, Long> {
    Page<ChunkEntity> findByDocumentIdOrderByChunkIndexAsc(String documentId, Pageable pageable);
    List<ChunkEntity> findByDocumentIdOrderByChunkIndexAsc(String documentId);
    int countByDocumentId(String documentId);
    void deleteByDocumentId(String documentId);

    /** Find up to 10 chunks whose text contains the given keyword (for concept linking). */
    @Query("SELECT c FROM ChunkEntity c WHERE c.text LIKE %:keyword%")
    List<ChunkEntity> findTop10ByTextContaining(@Param("keyword") String keyword, Pageable pageable);
}
