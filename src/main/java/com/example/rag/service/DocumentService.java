package com.example.rag.service;

import com.example.rag.entity.DocumentEntity;
import com.example.rag.model.*;
import com.example.rag.repository.DocumentRepository;
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
    private final MinioService minioService;

    public DocumentService(ChunkingService chunkingService, EmbeddingService embeddingService,
                           MilvusService milvusService, DocumentRepository documentRepository,
                           MinioService minioService) {
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.milvusService = milvusService;
        this.documentRepository = documentRepository;
        this.minioService = minioService;
    }

    // ========== Document-level APIs ==========

    public Map<String, Object> listDocuments(int page, int size, String keyword) {
        Page<DocumentEntity> pageResult;
        if (keyword != null && !keyword.isBlank()) {
            pageResult = documentRepository.findByTitleContainingIgnoreCase(keyword, PageRequest.of(page - 1, size));
        } else {
            pageResult = documentRepository.findAll(PageRequest.of(page - 1, size));
        }

        List<DocumentSummaryResponse> items = pageResult.getContent().stream()
                .map(d -> new DocumentSummaryResponse(
                        d.getDocumentId(), d.getTitle(), d.getSource(), d.getChunkCount(),
                        d.getChunkSize(), d.getOverlapSize(), d.getChunkMode(),
                        d.getMinioPath(), d.getCreatedAt()))
                .toList();

        return Map.of("items", items, "total", pageResult.getTotalElements(), "page", page, "size", size);
    }

    public DocumentSummaryResponse getDocument(String documentId) {
        DocumentEntity d = documentRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));
        return new DocumentSummaryResponse(
                d.getDocumentId(), d.getTitle(), d.getSource(), d.getChunkCount(),
                d.getChunkSize(), d.getOverlapSize(), d.getChunkMode(),
                d.getMinioPath(), d.getCreatedAt());
    }

    public ChunkListResponse getDocumentChunks(String documentId, int page, int size) {
        List<DocumentResponse> allChunks = milvusService.listByDocumentId(documentId);
        long total = allChunks.size();
        int from = (page - 1) * size;
        int to = (int) Math.min(from + size, total);
        if (from >= total) return new ChunkListResponse(List.of(), total, page, size);
        return new ChunkListResponse(allChunks.subList(from, to), total, page, size);
    }

    public String getDownloadUrl(String documentId) {
        DocumentEntity d = documentRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));
        if (d.getMinioPath() == null) {
            throw new NoSuchElementException("No original file for document: " + documentId);
        }
        try {
            return minioService.getPresignedUrl(d.getMinioPath());
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate download URL", e);
        }
    }

    // ========== Ingest / Create ==========

    @Transactional
    public Map<String, Object> ingest(String title, String content, String source,
                                       int chunkSize, int overlapSize, String chunkMode) {
        String documentId = UUID.randomUUID().toString();
        List<String> chunks = chunkingService.chunk(content, chunkSize, overlapSize, chunkMode);
        if (chunks.isEmpty()) {
            log.warn("No chunks generated for document '{}'", title);
            return Map.of("documentId", documentId, "chunks", 0);
        }

        List<String> titles = new ArrayList<>(Collections.nCopies(chunks.size(), title));
        List<List<Float>> embeddings = embeddingService.encode(chunks);
        milvusService.insertChunks(documentId, titles, chunks, embeddings);

        DocumentEntity doc = new DocumentEntity(documentId, title, source,
                chunks.size(), chunkSize, overlapSize, chunkMode);
        documentRepository.save(doc);

        log.info("Ingested document '{}': {} chunks", title, chunks.size());
        return Map.of("documentId", documentId, "chunks", chunks.size(), "title", title);
    }

    @Transactional
    public Map<String, Object> ingestFromFile(String title, InputStream fileStream, long fileSize,
                                               String contentType, String fileName, String source,
                                               int chunkSize, int overlapSize, String chunkMode) {
        try {
            String text = new org.apache.tika.Tika().parseToString(fileStream);
            String documentId = UUID.randomUUID().toString();

            // Upload original to MinIO
            String minioPath = documentId + "/" + fileName;
            try {
                minioService.upload(minioPath, fileStream, fileSize, contentType);
            } catch (Exception e) {
                log.warn("Failed to upload to MinIO: {}", e.getMessage());
                minioPath = null;
            }

            // Chunk and ingest
            List<String> chunks = chunkingService.chunk(text, chunkSize, overlapSize, chunkMode);
            if (chunks.isEmpty()) {
                return Map.of("documentId", documentId, "chunks", 0);
            }

            List<String> titles = new ArrayList<>(Collections.nCopies(chunks.size(), title));
            List<List<Float>> embeddings = embeddingService.encode(chunks);
            milvusService.insertChunks(documentId, titles, chunks, embeddings);

            DocumentEntity doc = new DocumentEntity(documentId, title, source,
                    chunks.size(), chunkSize, overlapSize, chunkMode);
            doc.setMinioPath(minioPath);
            documentRepository.save(doc);

            return Map.of("documentId", documentId, "chunks", chunks.size(), "title", title);
        } catch (Exception e) {
            throw new RuntimeException("Failed to ingest file: " + e.getMessage(), e);
        }
    }

    // ========== Delete ==========

    @Transactional
    public void deleteDocument(String documentId) {
        milvusService.deleteByDocumentId(documentId);
        documentRepository.deleteByDocumentId(documentId);
        log.info("Deleted document: {}", documentId);
    }

    @Transactional
    public void deleteChunk(String documentId, long chunkId) {
        milvusService.deleteById(chunkId);
        DocumentEntity doc = documentRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + documentId));
        doc.setChunkCount(Math.max(0, doc.getChunkCount() - 1));
        doc.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(doc);
    }

    @Transactional
    public int deleteBySource(String source) {
        List<DocumentEntity> docs = documentRepository.findAll()
                .stream().filter(d -> source.equals(d.getSource())).toList();
        for (DocumentEntity d : docs) {
            milvusService.deleteByDocumentId(d.getDocumentId());
            documentRepository.delete(d);
        }
        log.info("Deleted {} documents with source={}", docs.size(), source);
        return docs.size();
    }

    @Transactional
    public int deleteBySyncBatchId(String syncBatchId) {
        List<DocumentEntity> docs = documentRepository.findAll()
                .stream().filter(d -> syncBatchId.equals(d.getSyncBatchId())).toList();
        for (DocumentEntity d : docs) {
            milvusService.deleteByDocumentId(d.getDocumentId());
            documentRepository.delete(d);
        }
        log.info("Deleted {} documents with syncBatchId={}", docs.size(), syncBatchId);
        return docs.size();
    }

    // ========== Legacy compat / internal ==========

    public DocumentResponse getById(long id) {
        return milvusService.getById(id);
    }

    public void deleteById(long id) {
        milvusService.deleteById(id);
    }
}
