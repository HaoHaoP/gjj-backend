package com.haohaop.rag.service;

import com.haohaop.rag.entity.ChunkEntity;
import com.haohaop.rag.entity.DocumentEntity;
import com.haohaop.rag.model.*;
import com.haohaop.rag.repository.ChunkRepository;
import com.haohaop.rag.repository.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class DocumentService {

    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final MinioService minioService;

    public DocumentService(ChunkingService chunkingService, EmbeddingService embeddingService,
                           MilvusService milvusService, DocumentRepository documentRepository,
                           ChunkRepository chunkRepository, MinioService minioService) {
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.milvusService = milvusService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.minioService = minioService;
    }

    // ========== Document-level APIs ==========

    public Map<String, Object> listDocuments(int page, int size, String keyword) {
        Page<DocumentEntity> pageResult;
        if (keyword != null && !keyword.isBlank()) {
            pageResult = documentRepository.findByTitleContainingIgnoreCaseOrderByCreatedAtDesc(
                    keyword, PageRequest.of(page - 1, size));
        } else {
            pageResult = documentRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page - 1, size));
        }

        List<DocumentSummaryResponse> items = pageResult.getContent().stream()
                .map(DocumentSummaryResponse::from)
                .toList();

        return Map.of("items", items, "total", pageResult.getTotalElements(), "page", page, "size", size);
    }

    public DocumentSummaryResponse getDocument(String documentId) {
        DocumentEntity d = documentRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));
        return DocumentSummaryResponse.from(d);
    }

    public ChunkListResponse getDocumentChunks(String documentId, int page, int size) {
        Page<ChunkEntity> pageResult = chunkRepository
                .findByDocumentIdOrderByChunkIndexAsc(documentId, PageRequest.of(page - 1, size));
        List<DocumentResponse> items = pageResult.getContent().stream()
                .map(c -> new DocumentResponse(c.getId(), c.getParentTitle(), c.getText()))
                .toList();
        return new ChunkListResponse(items, pageResult.getTotalElements(), page, size);
    }

    public String getDownloadUrl(String documentId) {
        DocumentEntity d = documentRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));
        if (d.getMinioPath() == null) {
            throw new NoSuchElementException("No original file for document: " + documentId);
        }
        try {
            return minioService.getPresignedUrl(d.getMinioPath(),
                    d.getOriginalFilename() != null ? d.getOriginalFilename() : d.getTitle() + ".html");
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate download URL", e);
        }
    }

    // ========== Ingest / Create ==========

    @Transactional
    public Map<String, Object> ingest(String title, String content, String source,
                                       int chunkSize, int overlapSize, String chunkMode) {
        return ingestInternal(title, content, null, null, 0, source, chunkSize, overlapSize, chunkMode);
    }

    @Transactional
    public Map<String, Object> ingestFromFileBytes(String title, byte[] bytes, String text, long fileSize,
                                                    String source, int chunkSize, int overlapSize, String chunkMode,
                                                    String originalFilename) {
        try {
            String documentId = UUID.randomUUID().toString();
            String safeFilename = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename
                    : title + ".html";
            String minioPath = documentId + "/" + safeFilename;
            try {
                minioService.upload(minioPath, new java.io.ByteArrayInputStream(bytes),
                        bytes.length, "text/html");
            } catch (Exception e) {
                log.warn("Failed to upload to MinIO: {}", e.getMessage());
                minioPath = null;
            }
            return ingestInternal(title, text, minioPath, safeFilename, bytes.length, source,
                    chunkSize, overlapSize, chunkMode);
        } catch (Exception e) {
            throw new RuntimeException("Failed to ingest file: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Map<String, Object> ingestWithMinio(String title, String content, String source,
                                                String minioPath, String originalFilename, long fileSize) {
        return ingestInternal(title, content, minioPath, originalFilename, fileSize, source, 500, 0, "CLAUSE");
    }

    private Map<String, Object> ingestInternal(String title, String content,
                                                String minioPath, String originalFilename, long fileSize,
                                                String source, int chunkSize, int overlapSize, String chunkMode) {
        String documentId = UUID.randomUUID().toString();
        List<String> chunks = chunkingService.chunk(content, chunkSize, overlapSize, chunkMode);
        if (chunks.isEmpty()) {
            log.warn("No chunks generated for document '{}'", title);
            return Map.of("documentId", documentId, "chunks", 0);
        }

        // 1. Encode + Milvus
        List<String> titles = new ArrayList<>(Collections.nCopies(chunks.size(), title));
        List<List<Float>> embeddings = embeddingService.encode(chunks);
        milvusService.insertChunks(documentId, titles, chunks, embeddings);

        // 2. Write Document to PG
        DocumentEntity doc = new DocumentEntity(documentId, title, source,
                chunks.size(), chunkSize, overlapSize, chunkMode,
                minioPath, originalFilename, fileSize);
        documentRepository.save(doc);

        // 3. Write Chunks to PG (batch)
        List<ChunkEntity> chunkEntities = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            chunkEntities.add(new ChunkEntity(documentId, i + 1, chunks.get(i), null, title));
        }
        chunkRepository.saveAll(chunkEntities);

        log.info("Ingested document '{}': {} chunks", title, chunks.size());
        return Map.of("documentId", documentId, "chunks", chunks.size(), "title", title);
    }

    // ========== Delete ==========

    @Transactional
    public void deleteDocument(String documentId) {
        // Delete MinIO objects first
        DocumentEntity doc = documentRepository.findByDocumentId(documentId).orElse(null);
        if (doc != null && doc.getMinioPath() != null) {
            try {
                String prefix = doc.getMinioPath().substring(0, doc.getMinioPath().indexOf('/') + 1);
                minioService.deletePrefix(prefix);
            } catch (Exception e) { log.warn("MinIO delete failed: {}", e.getMessage()); }
        }
        milvusService.deleteByDocumentId(documentId);
        chunkRepository.deleteByDocumentId(documentId);
        documentRepository.deleteByDocumentId(documentId);
        log.info("Deleted document: {}", documentId);
    }

    @Transactional
    public void deleteChunk(String documentId, long chunkId) {
        milvusService.deleteById(chunkId);
        chunkRepository.deleteById(chunkId);
        DocumentEntity doc = documentRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));
        doc.setChunkCount(Math.max(0, doc.getChunkCount() - 1));
        doc.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(doc);
    }

    public DocumentResponse getById(long id) {
        return milvusService.getById(id);
    }
}
