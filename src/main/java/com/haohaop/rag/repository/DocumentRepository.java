package com.haohaop.rag.repository;

import com.haohaop.rag.entity.DocumentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    Optional<DocumentEntity> findByDocumentId(String documentId);

    Page<DocumentEntity> findByTitleContainingIgnoreCaseOrderByCreatedAtDesc(String keyword, Pageable pageable);

    Page<DocumentEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    void deleteByDocumentId(String documentId);
}
