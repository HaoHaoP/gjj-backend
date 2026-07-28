package com.example.rag.service;

import com.example.rag.entity.DocumentEntity;
import com.example.rag.model.*;
import com.example.rag.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock private ChunkingService chunkingService;
    @Mock private EmbeddingService embeddingService;
    @Mock private MilvusService milvusService;
    @Mock private DocumentRepository documentRepository;
    @Mock private MinioService minioService;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(chunkingService, embeddingService,
                milvusService, documentRepository, minioService);
    }

    // ========== listDocuments ==========

    @Test
    void shouldListDocuments() {
        DocumentEntity doc = new DocumentEntity("uuid-1", "测试条例", "SYNC", 5, 500, 0, "SENTENCE");
        when(documentRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(doc)));

        Map<String, Object> result = documentService.listDocuments(1, 20, null);

        assertEquals(1L, result.get("total"));
        List<DocumentSummaryResponse> items = (List<DocumentSummaryResponse>) result.get("items");
        assertEquals(1, items.size());
        assertEquals("uuid-1", items.get(0).documentId());
        assertEquals("测试条例", items.get(0).title());
        assertEquals("SYNC", items.get(0).source());
        assertEquals(5, items.get(0).chunkCount());
    }

    @Test
    void shouldListDocumentsWithKeyword() {
        when(documentRepository.findByTitleContainingIgnoreCase(eq("公积金"), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));

        Map<String, Object> result = documentService.listDocuments(1, 20, "公积金");
        assertEquals(0L, result.get("total"));
    }

    // ========== getDocument ==========

    @Test
    void shouldGetDocumentById() {
        DocumentEntity doc = new DocumentEntity("uuid-2", "管理条例", "UPLOAD", 10, 300, 50, "FIXED");
        when(documentRepository.findByDocumentId("uuid-2")).thenReturn(Optional.of(doc));

        DocumentSummaryResponse result = documentService.getDocument("uuid-2");
        assertEquals("管理条例", result.title());
        assertEquals(10, result.chunkCount());
        assertEquals("FIXED", result.chunkMode());
    }

    @Test
    void shouldThrowWhenDocumentNotFound() {
        when(documentRepository.findByDocumentId("nonexistent")).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> documentService.getDocument("nonexistent"));
    }

    // ========== getDocumentChunks ==========

    @Test
    void shouldListChunksForDocument() {
        DocumentResponse chunk = new DocumentResponse(1L, "法规A", "第三条、职工应当...");
        when(milvusService.listByDocumentId("uuid-3")).thenReturn(List.of(chunk));

        ChunkListResponse result = documentService.getDocumentChunks("uuid-3", 1, 50);
        assertEquals(1, result.total());
        assertEquals("法规A", result.items().get(0).title());
    }

    // ========== ingest ==========

    @Test
    void shouldIngestDocument() {
        when(chunkingService.chunk(anyString(), eq(500), eq(0), eq("SENTENCE")))
                .thenReturn(List.of("chunk1", "chunk2"));
        when(embeddingService.encode(anyList()))
                .thenReturn(List.of(List.of(1.0f), List.of(2.0f)));
        when(milvusService.insertChunks(anyString(), anyList(), anyList(), anyList()))
                .thenReturn(List.of(1L, 2L));

        Map<String, Object> result = documentService.ingest("测试文档", "正文内容...", "MANUAL", 500, 0, "SENTENCE");

        assertEquals("测试文档", result.get("title"));
        assertEquals(2, result.get("chunks"));
        assertNotNull(result.get("documentId"));

        // Verify entity saved
        ArgumentCaptor<DocumentEntity> captor = ArgumentCaptor.forClass(DocumentEntity.class);
        verify(documentRepository).save(captor.capture());
        assertEquals("MANUAL", captor.getValue().getSource());
        assertEquals(2, captor.getValue().getChunkCount());
    }

    @Test
    void shouldIngestWithCustomChunkParams() {
        when(chunkingService.chunk(anyString(), eq(300), eq(50), eq("FIXED")))
                .thenReturn(List.of("chunk1"));
        when(embeddingService.encode(anyList())).thenReturn(List.of(List.of(1.0f)));
        when(milvusService.insertChunks(anyString(), anyList(), anyList(), anyList()))
                .thenReturn(List.of(1L));

        Map<String, Object> result = documentService.ingest("文档", "内容", "MANUAL", 300, 50, "FIXED");

        assertEquals(1, result.get("chunks"));
        verify(chunkingService).chunk(anyString(), eq(300), eq(50), eq("FIXED"));
    }

    @Test
    void shouldHandleEmptyChunks() {
        when(chunkingService.chunk(anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(List.of());

        Map<String, Object> result = documentService.ingest("空文档", "。", "MANUAL", 500, 0, "SENTENCE");
        assertEquals(0, result.get("chunks"));
        verify(documentRepository, never()).save(any());
    }

    // ========== deleteDocument ==========

    @Test
    void shouldDeleteDocumentAndChunks() {
        documentService.deleteDocument("uuid-5");
        verify(milvusService).deleteByDocumentId("uuid-5");
        verify(documentRepository).deleteByDocumentId("uuid-5");
    }

    // ========== deleteBySource ==========

    @Test
    void shouldDeleteAllSyncDocuments() {
        DocumentEntity d1 = new DocumentEntity("uuid-a", "同步文档A", "SYNC", 5, 500, 0, "SENTENCE");
        DocumentEntity d2 = new DocumentEntity("uuid-b", "上传文档B", "UPLOAD", 3, 400, 25, "SENTENCE");
        when(documentRepository.findAll()).thenReturn(List.of(d1, d2));

        int deleted = documentService.deleteBySource("SYNC");
        assertEquals(1, deleted);
        verify(milvusService).deleteByDocumentId("uuid-a");
        verify(milvusService, never()).deleteByDocumentId("uuid-b");
    }

    // ========== deleteChunk ==========

    @Test
    void shouldDeleteChunkAndUpdateCount() {
        DocumentEntity doc = new DocumentEntity("uuid-c", "文档C", "MANUAL", 5, 500, 0, "SENTENCE");
        when(documentRepository.findByDocumentId("uuid-c")).thenReturn(Optional.of(doc));

        documentService.deleteChunk("uuid-c", 10L);
        verify(milvusService).deleteById(10L);
        assertEquals(4, doc.getChunkCount());
        verify(documentRepository).save(doc);
    }
}
