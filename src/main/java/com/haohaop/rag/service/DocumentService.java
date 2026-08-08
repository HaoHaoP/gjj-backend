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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.*;
import org.springframework.transaction.annotation.Transactional;
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

    /** 自注入，确保从线程池调用时 @Transactional 生效。 */
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private DocumentService self;

    /** Milvus varchar(4096) 的最大 UTF-8 字节数。 */
    private static final int MAX_CHUNK_BYTES = 4096;

    /** 匹配子项标记的正则，如（一）（二）或 1、2、 */
    private static final Pattern ITEM_PATTERN =
            Pattern.compile("([（(][一二三四五六七八九十百千]+[）)]|\\d+[、。．])");

    // ── UTF-8 工具方法 ──

    private static int utf8Bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    static String truncateUtf8(String s, int maxBytes) {
        if (s == null || s.isEmpty()) return s;
        if (utf8Bytes(s) <= maxBytes) return s;
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int cb = utf8CharBytes(c, s, i);
            if (used + cb > maxBytes) break;
            sb.append(c);
            if (cb == 4) { sb.append(s.charAt(++i)); }
            used += cb;
        }
        return sb.toString();
    }

    static int utf8CharBytes(char c, String s, int idx) {
        if (c < 0x80) return 1;
        if (c < 0x800) return 2;
        if (!Character.isSurrogate(c)) return 3;
        if (Character.isHighSurrogate(c) && idx + 1 < s.length()
                && Character.isLowSurrogate(s.charAt(idx + 1))) return 4;
        return 3;
    }

    static int countUtf8Bytes(CharSequence cs) {
        int n = 0;
        for (int i = 0; i < cs.length(); i++) {
            n += utf8CharBytes(cs.charAt(i), cs.toString(), i);
        }
        return n;
    }

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

    // ========== 文档级接口 ==========

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
                .orElseThrow(() -> new NoSuchElementException("文档不存在：" + documentId));
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
                .orElseThrow(() -> new NoSuchElementException("文档不存在：" + documentId));
        if (d.getMinioPath() == null) {
            throw new NoSuchElementException("文档没有原始文件：" + documentId);
        }
        try {
            return minioService.getPresignedUrl(d.getMinioPath(),
                    d.getOriginalFilename() != null ? d.getOriginalFilename() : d.getTitle() + ".html");
        } catch (Exception e) {
            throw new RuntimeException("生成下载地址失败", e);
        }
    }

    // ========== 入库 / 创建 ==========

    @Transactional
    public Map<String, Object> ingest(String title, String content, String source,
                                       String minioPath, String originalFilename,
                                       int chunkSize, int overlapSize, String chunkMode) {
        long fileSize = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        Map<String, Object> result = ingestInternal(title, content,
                minioPath, originalFilename, fileSize, source, chunkSize, overlapSize, chunkMode);

        // 入库成功后再写入 MinIO——MinIO 中的文件即为完整处理后的文档
        if (minioPath != null && !minioPath.isBlank()) {
            try {
                byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                minioService.upload(minioPath, new java.io.ByteArrayInputStream(bytes),
                        bytes.length, "text/markdown");
                log.info("MinIO 上传完成：{}", minioPath);
            } catch (Exception e) {
                log.error("入库后 MinIO 上传失败（数据已在 PG/Milvus 中）：{}", minioPath, e);
            }
        }
        return result;
    }

    @Transactional
    public Map<String, Object> ingestFromFileBytes(String title, byte[] bytes, String text,
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
                log.warn("上传到 MinIO 失败：{}", e.getMessage());
                minioPath = null;
            }
            return ingestInternal(title, text, minioPath, safeFilename, bytes.length, source,
                    chunkSize, overlapSize, chunkMode);
        } catch (Exception e) {
            throw new RuntimeException("文件入库失败：" + e.getMessage(), e);
        }
    }

    private Map<String, Object> ingestInternal(String title, String content,
                                                String minioPath, String originalFilename, long fileSize,
                                                String source, int chunkSize, int overlapSize, String chunkMode) {
        String documentId = UUID.randomUUID().toString();

        // CLAUSE 模式使用结构化分块，以获取条款编号和父级标题
        List<ChunkingService.ChunkSegment> rawSegments =
                chunkingService.chunkStructured(content, chunkSize, overlapSize, chunkMode);

        // 在条目/子条目边界拆分过长分块，保留条款元数据
        List<ChunkingService.ChunkSegment> segments = splitLongSegments(rawSegments);

        // 构建分块列表
        List<String> chunkTexts = new ArrayList<>();
        List<String> clauseNumbers = new ArrayList<>();
        List<String> parentTitles = new ArrayList<>();
        List<String> pgTexts = new ArrayList<>(); // PG 中保存的完整文本

        for (ChunkingService.ChunkSegment seg : segments) {
            String milvusText = seg.text().length() > MAX_CHUNK_BYTES
                    ? truncateUtf8(seg.text(), MAX_CHUNK_BYTES) : seg.text();
            chunkTexts.add(milvusText);
            pgTexts.add(seg.text()); // PG 保存完整文本
            clauseNumbers.add(seg.clauseNumber());
            parentTitles.add(buildParentTitle(title, seg.chapterTitle(), seg.sectionTitle()));
        }

        if (chunkTexts.isEmpty()) {
            log.warn("文档『{}』未生成任何分块", title);
            return Map.of("documentId", documentId, "chunks", 0);
        }

        // 1. 向量化并写入 Milvus（截断后的文本）
        List<String> titles = new ArrayList<>(Collections.nCopies(chunkTexts.size(), title));
        List<List<Float>> embeddings = embeddingService.encodeBatch(chunkTexts);
        milvusService.insertChunks(documentId, titles, chunkTexts, embeddings);

        // 2. 写入 Document 到 PG
        DocumentEntity doc = new DocumentEntity(documentId, title, source,
                segments.size(), chunkSize, overlapSize, chunkMode,
                minioPath, originalFilename, fileSize);
        documentRepository.save(doc);

        // 3. 将完整文本及条款元数据写入 Chunks 到 PG
        List<ChunkEntity> chunkEntities = new ArrayList<>();
        for (int i = 0; i < pgTexts.size(); i++) {
            chunkEntities.add(new ChunkEntity(documentId, i + 1, pgTexts.get(i),
                    clauseNumbers.get(i), parentTitles.get(i)));
        }
        chunkRepository.saveAll(chunkEntities);

        log.info("文档『{}』入库完成：{} 个分块（其中 {} 个由长条款拆分而来）",
                title, segments.size(),
                segments.size() - rawSegments.size());
        return Map.of("documentId", documentId, "chunks", segments.size(), "title", title);
    }

    /**
     * 在条目边界拆分超过 MAX_CHUNK_BYTES 的分块。
     * 每个子分块继承相同的条款元数据，便于上下文关联。
     */
    private List<ChunkingService.ChunkSegment> splitLongSegments(
            List<ChunkingService.ChunkSegment> raw) {
        List<ChunkingService.ChunkSegment> result = new ArrayList<>();

        for (ChunkingService.ChunkSegment seg : raw) {
            if (utf8Bytes(seg.text()) <= MAX_CHUNK_BYTES) {
                result.add(seg);
                continue;
            }

            log.debug("正在拆分长条款『{}』（{} 字符）", seg.clauseNumber(), seg.text().length());
            List<String> parts = splitAtItems(seg.text());

            for (String part : parts) {
                result.add(new ChunkingService.ChunkSegment(
                        part, seg.clauseNumber(), seg.chapterTitle(), seg.sectionTitle()));
            }
        }
        return result;
    }

    /**
     * 在条目标记（如（一）（二））或句子边界处拆分文本。
     * 若未找到条目标记，则回退为按句拆分。
     */
    private List<String> splitAtItems(String text) {
        List<String> parts = new ArrayList<>();
        Matcher m = ITEM_PATTERN.matcher(text);
        List<Integer> positions = new ArrayList<>();

        while (m.find()) {
            positions.add(m.start());
        }

        if (positions.size() >= 2) {
            // 在条目边界处拆分
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
            // 没有条目标记——按句子边界拆分
            parts = splitAtSentences(text);
        }

        // 若仍有部分过长，则强制截断
        List<String> finalParts = new ArrayList<>();
        for (String p : parts) {
            if (utf8Bytes(p) > MAX_CHUNK_BYTES) {
                finalParts.add(truncateUtf8(p, MAX_CHUNK_BYTES));
            } else {
                finalParts.add(p);
            }
        }
        return finalParts;
    }

    /** 按句子边界（。！？\n）拆分，合并至接近 MAX_CHUNK_BYTES。 */
    private List<String> splitAtSentences(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int bufBytes = 0;
        int third = MAX_CHUNK_BYTES / 3;
        int half  = MAX_CHUNK_BYTES / 2;

        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int added = utf8CharBytes(c, text, i);
            buf.append(c);
            if (added == 4) { i++; buf.append(text.charAt(i)); }
            bufBytes += added;
            i++;

            boolean isSentenceEnd = (c == '。' || c == '！' || c == '？' || c == '\n')
                    && bufBytes > third;

            if (isSentenceEnd && bufBytes >= half) {
                result.add(buf.toString().trim());
                buf.setLength(0);
                bufBytes = 0;
            } else if (bufBytes >= MAX_CHUNK_BYTES) {
                int splitAt = findLastSentenceEnd(buf);
                if (splitAt > 0) {
                    result.add(buf.substring(0, splitAt).trim());
                    buf.replace(0, buf.length(), buf.substring(splitAt));
                    bufBytes = countUtf8Bytes(buf);
                } else {
                    result.add(buf.toString().trim());
                    buf.setLength(0);
                    bufBytes = 0;
                }
            }
        }

        if (!buf.isEmpty()) {
            result.add(buf.toString().trim());
        }

        // 若只有一部分（无法拆分），保持原样
        return result.size() <= 1 ? List.of(text) : result;
    }

    /** 在缓冲区中查找最后一个句子结束符。返回其后的位置，若不存在返回 -1。 */
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

    // ========== 删除 ==========

    @Transactional
    public void deleteDocument(String documentId) {
        DocumentEntity doc = documentRepository.findByDocumentId(documentId).orElse(null);
        if (doc != null && doc.getMinioPath() != null) {
            try {
                String prefix = doc.getMinioPath().substring(0, doc.getMinioPath().indexOf('/') + 1);
                minioService.deletePrefix(prefix);
            } catch (Exception e) { log.warn("MinIO 删除失败：{}", e.getMessage()); }
        }
        milvusService.deleteByDocumentId(documentId);
        chunkRepository.deleteByDocumentId(documentId);
        documentRepository.deleteByDocumentId(documentId);
        try {
            knowledgeGraphService.deleteByDocumentId(documentId);
        } catch (Exception e) {
            log.warn("文档 {} 的知识图谱清理失败：{}", documentId, e.getMessage());
        }
        log.info("已删除文档：{}", documentId);
    }

    @Transactional
    public void deleteChunk(String documentId, long chunkId) {
        milvusService.deleteById(chunkId);
        chunkRepository.deleteById(chunkId);
        DocumentEntity doc = documentRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new NoSuchElementException("文档不存在：" + documentId));
        doc.setChunkCount(Math.max(0, doc.getChunkCount() - 1));
        doc.setUpdatedAt(LocalDateTime.now());
        documentRepository.save(doc);
    }

    public int deleteBatch(java.util.List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) return 0;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(
                Math.min(documentIds.size(), 4));
        java.util.concurrent.atomic.AtomicInteger deleted = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (String id : documentIds) {
            futures.add(executor.submit(() -> {
                try {
                    self.deleteDocument(id);  // 通过代理调用 → @Transactional 生效
                    deleted.incrementAndGet();
                } catch (Exception e) {
                    log.warn("批量删除失败（{}）：{}", id, e.getMessage());
                }
            }));
        }

        for (var f : futures) {
            try { f.get(30, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        executor.shutdown();
        return deleted.get();
    }

    public DocumentResponse getById(long id) {
        return milvusService.getById(id);
    }
}
