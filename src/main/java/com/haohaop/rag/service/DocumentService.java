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
import java.util.regex.*;

@Slf4j
@Service
public class DocumentService {

    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final KnowledgeGraphService knowledgeGraphService;
    private final MinioService minioService;

    /** Milvus varchar max: 4096 chars. Longer chunks are split at item boundaries. */
    private static final int MAX_CHUNK_TEXT = 4096;

    /** Pattern for sub-item markers like （一）（二） or 1、2、 */
    private static final Pattern ITEM_PATTERN =
            Pattern.compile("([（(][一二三四五六七八九十百千]+[）)]|\\d+[、。．])");

    public DocumentService(ChunkingService chunkingService, EmbeddingService embeddingService,
                           MilvusService milvusService, DocumentRepository documentRepository,
                           ChunkRepository chunkRepository, MinioService minioService,
                           KnowledgeGraphService knowledgeGraphService) {
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.milvusService = milvusService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.minioService = minioService;
        this.knowledgeGraphService = knowledgeGraphService;
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
                .map(c -> new DocumentResponse(c.getId(), c.getParentTitle(), c.getText(), c.getClauseNumber()))
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
                                       String minioPath, String originalFilename,
                                       int chunkSize, int overlapSize, String chunkMode) {
        long fileSize = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        Map<String, Object> result = ingestInternal(title, content,
                minioPath, originalFilename, fileSize, source, chunkSize, overlapSize, chunkMode);

        // Write to MinIO AFTER successful ingest — MinIO file = fully processed document
        if (minioPath != null && !minioPath.isBlank()) {
            try {
                byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                minioService.upload(minioPath, new java.io.ByteArrayInputStream(bytes),
                        bytes.length, "text/markdown");
                log.info("MinIO upload complete: {}", minioPath);
            } catch (Exception e) {
                log.error("MinIO upload failed after ingest (data already in PG/Milvus): {}", minioPath, e);
            }
        }
        return result;
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

    private Map<String, Object> ingestInternal(String title, String content,
                                                String minioPath, String originalFilename, long fileSize,
                                                String source, int chunkSize, int overlapSize, String chunkMode) {
        String documentId = UUID.randomUUID().toString();

        // Use structured chunking for CLAUSE mode to capture clauseNumber and parentTitle
        List<ChunkingService.ChunkSegment> rawSegments =
                chunkingService.chunkStructured(content, chunkSize, overlapSize, chunkMode);

        // Split long segments at item/sub-item boundaries, preserving clause metadata
        List<ChunkingService.ChunkSegment> segments = splitLongSegments(rawSegments);

        // Build chunk lists
        List<String> chunkTexts = new ArrayList<>();
        List<String> clauseNumbers = new ArrayList<>();
        List<String> parentTitles = new ArrayList<>();
        List<String> pgTexts = new ArrayList<>(); // full text for PG

        for (ChunkingService.ChunkSegment seg : segments) {
            String milvusText = seg.text().length() > MAX_CHUNK_TEXT
                    ? seg.text().substring(0, MAX_CHUNK_TEXT) : seg.text();
            chunkTexts.add(milvusText);
            pgTexts.add(seg.text()); // PG stores full text
            clauseNumbers.add(seg.clauseNumber());
            parentTitles.add(buildParentTitle(title, seg.chapterTitle(), seg.sectionTitle()));
        }

        if (chunkTexts.isEmpty()) {
            log.warn("No chunks generated for document '{}'", title);
            return Map.of("documentId", documentId, "chunks", 0);
        }

        // 1. Encode + Milvus (truncated text)
        List<String> titles = new ArrayList<>(Collections.nCopies(chunkTexts.size(), title));
        List<List<Float>> embeddings = embeddingService.encode(chunkTexts);
        milvusService.insertChunks(documentId, titles, chunkTexts, embeddings);

        // 2. Write Document to PG
        DocumentEntity doc = new DocumentEntity(documentId, title, source,
                segments.size(), chunkSize, overlapSize, chunkMode,
                minioPath, originalFilename, fileSize);
        documentRepository.save(doc);

        // 3. Write Chunks to PG with full text and clause metadata
        List<ChunkEntity> chunkEntities = new ArrayList<>();
        for (int i = 0; i < pgTexts.size(); i++) {
            chunkEntities.add(new ChunkEntity(documentId, i + 1, pgTexts.get(i),
                    clauseNumbers.get(i), parentTitles.get(i)));
        }
        chunkRepository.saveAll(chunkEntities);

        log.info("Ingested document '{}': {} chunks ({} expanded from long clauses)",
                title, segments.size(),
                segments.size() - rawSegments.size());
        return Map.of("documentId", documentId, "chunks", segments.size(), "title", title);
    }

    /**
     * Split segments that exceed MAX_CHUNK_TEXT at item boundaries.
     * Each sub-chunk inherits the same clause metadata for context association.
     */
    private List<ChunkingService.ChunkSegment> splitLongSegments(
            List<ChunkingService.ChunkSegment> raw) {
        List<ChunkingService.ChunkSegment> result = new ArrayList<>();

        for (ChunkingService.ChunkSegment seg : raw) {
            if (seg.text().length() <= MAX_CHUNK_TEXT || seg.clauseNumber() == null) {
                result.add(seg);
                continue;
            }

            log.debug("Splitting long clause '{}' ({} chars)", seg.clauseNumber(), seg.text().length());
            List<String> parts = splitAtItems(seg.text());

            for (String part : parts) {
                result.add(new ChunkingService.ChunkSegment(
                        part, seg.clauseNumber(), seg.chapterTitle(), seg.sectionTitle()));
            }
        }
        return result;
    }

    /**
     * Split text at item markers like （一）（二） or sentence breaks.
     * Falls back to sentence break if no items found.
     */
    private List<String> splitAtItems(String text) {
        List<String> parts = new ArrayList<>();
        Matcher m = ITEM_PATTERN.matcher(text);
        List<Integer> positions = new ArrayList<>();

        while (m.find()) {
            positions.add(m.start());
        }

        if (positions.size() >= 2) {
            // Split at item boundaries
            if (positions.get(0) > 0) {
                parts.add(text.substring(0, positions.get(0)).trim());
            }
            for (int i = 0; i < positions.size(); i++) {
                int start = positions.get(i);
                int end = (i + 1 < positions.size()) ? positions.get(i + 1) : text.length();
                String part = text.substring(start, end).trim();
                if (!part.isEmpty()) {
                    parts.add(part);
                }
            }
        } else {
            // No item markers — split at sentence boundaries
            parts = splitAtSentences(text);
        }

        // If any part is still too long, hard-truncate
        List<String> finalParts = new ArrayList<>();
        for (String p : parts) {
            if (p.length() > MAX_CHUNK_TEXT) {
                finalParts.add(p.substring(0, MAX_CHUNK_TEXT));
            } else {
                finalParts.add(p);
            }
        }
        return finalParts;
    }

    /** Split at sentence boundaries (。！？\n), merging until close to MAX_CHUNK_TEXT. */
    private List<String> splitAtSentences(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            buf.append(c);

            boolean isSentenceEnd = (c == '。' || c == '！' || c == '？' || c == '\n')
                    && buf.length() > MAX_CHUNK_TEXT / 3;

            if (isSentenceEnd && buf.length() >= MAX_CHUNK_TEXT / 2) {
                result.add(buf.toString().trim());
                buf.setLength(0);
            } else if (buf.length() >= MAX_CHUNK_TEXT) {
                int splitAt = findLastSentenceEnd(buf);
                if (splitAt > 0) {
                    result.add(buf.substring(0, splitAt).trim());
                    buf.replace(0, buf.length(), buf.substring(splitAt));
                } else {
                    result.add(buf.toString().trim());
                    buf.setLength(0);
                }
            }
        }

        if (buf.length() > 0) {
            result.add(buf.toString().trim());
        }

        // If only one part (can't split), keep as-is
        return result.size() <= 1 ? List.of(text) : result;
    }

    /** Find the last sentence-ending char within the buffer. Returns the position after it, or -1. */
    private int findLastSentenceEnd(CharSequence buf) {
        for (int j = buf.length() - 1; j >= 0; j--) {
            char c = buf.charAt(j);
            if (c == '。' || c == '！' || c == '？' || c == '\n') {
                return j + 1;
            }
        }
        return -1;
    }

    private String buildParentTitle(String docTitle, String chapter, String section) {
        if (chapter == null && section == null) return docTitle;
        StringBuilder sb = new StringBuilder(docTitle);
        if (chapter != null) sb.append(" > ").append(chapter);
        if (section != null) sb.append(" > ").append(section);
        return sb.toString();
    }

    // ========== Delete ==========

    @Transactional
    public void deleteDocument(String documentId) {
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
        try {
            knowledgeGraphService.deleteByDocumentId(documentId);
        } catch (Exception e) {
            log.warn("KG cleanup failed for document {}: {}", documentId, e.getMessage());
        }
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
