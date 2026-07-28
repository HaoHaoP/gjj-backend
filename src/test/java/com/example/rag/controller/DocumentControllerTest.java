package com.example.rag.controller;

import com.example.rag.model.*;
import com.example.rag.service.DocumentService;
import com.example.rag.service.SyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private DocumentService documentService;
    @MockBean private SyncService syncService;

    @Test
    void shouldListDocuments() throws Exception {
        when(documentService.listDocuments(1, 20, null))
                .thenReturn(Map.of("items", List.of(), "total", 0L, "page", 1, "size", 20));

        mockMvc.perform(get("/api/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void shouldListDocumentsWithPagination() throws Exception {
        when(documentService.listDocuments(2, 10, null))
                .thenReturn(Map.of("items", List.of(), "total", 0L, "page", 2, "size", 10));

        mockMvc.perform(get("/api/documents?page=2&size=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    void shouldListDocumentsWithKeyword() throws Exception {
        when(documentService.listDocuments(eq(1), eq(20), eq("公积金")))
                .thenReturn(Map.of("items", List.of(), "total", 0L, "page", 1, "size", 20));

        mockMvc.perform(get("/api/documents?keyword=公积金"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldGetDocumentById() throws Exception {
        DocumentSummaryResponse doc = new DocumentSummaryResponse(
                "uuid-1", "测试条例", "SYNC", 5, 500, 0, "SENTENCE", null, null);
        when(documentService.getDocument("uuid-1")).thenReturn(doc);

        mockMvc.perform(get("/api/documents/uuid-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documentId").value("uuid-1"))
                .andExpect(jsonPath("$.title").value("测试条例"))
                .andExpect(jsonPath("$.source").value("SYNC"));
    }

    @Test
    void shouldListDocumentChunks() throws Exception {
        ChunkListResponse chunks = new ChunkListResponse(List.of(), 0, 1, 50);
        when(documentService.getDocumentChunks("uuid-2", 1, 50)).thenReturn(chunks);

        mockMvc.perform(get("/api/documents/uuid-2/chunks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void shouldIngestDocument() throws Exception {
        when(documentService.ingest(eq("测试"), anyString(), eq("MANUAL"), eq(500), eq(0), eq("SENTENCE")))
                .thenReturn(Map.of("documentId", "uuid-3", "chunks", 3, "title", "测试"));

        mockMvc.perform(post("/api/documents/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"测试","content":"正文内容...","chunkSize":500,"overlapSize":0,"chunkMode":"SENTENCE"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentId").value("uuid-3"))
                .andExpect(jsonPath("$.chunks").value(3));
    }

    @Test
    void shouldIngestWithCustomChunkParams() throws Exception {
        when(documentService.ingest(eq("文档"), anyString(), eq("MANUAL"), eq(300), eq(50), eq("FIXED")))
                .thenReturn(Map.of("documentId", "uuid-4", "chunks", 1, "title", "文档"));

        mockMvc.perform(post("/api/documents/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"文档","content":"内容","chunkSize":300,"overlapSize":50,"chunkMode":"FIXED"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldRejectIngestWithoutTitle() throws Exception {
        mockMvc.perform(post("/api/documents/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"正文\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldDeleteDocument() throws Exception {
        mockMvc.perform(delete("/api/documents/uuid-delete"))
                .andExpect(status().isNoContent());
    }

    @Test
    void shouldDeleteChunk() throws Exception {
        mockMvc.perform(delete("/api/documents/uuid-delete/chunks/42"))
                .andExpect(status().isNoContent());
    }

    @Test
    void shouldStartSync() throws Exception {
        when(documentService.deleteBySource("SYNC")).thenReturn(3);
        when(syncService.startSync(500, 0, "SENTENCE")).thenReturn("task-123");

        mockMvc.perform(post("/api/documents/sync"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("task-123"))
                .andExpect(jsonPath("$.status").value("running"));
    }

    @Test
    void shouldStartSyncWithCustomParams() throws Exception {
        when(documentService.deleteBySource("SYNC")).thenReturn(0);
        when(syncService.startSync(300, 25, "FIXED")).thenReturn("task-456");

        mockMvc.perform(post("/api/documents/sync?chunkSize=300&overlapSize=25&chunkMode=FIXED"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value("task-456"));
    }

    @Test
    void shouldGetSyncStatus() throws Exception {
        when(syncService.getStatus("task-789")).thenReturn("done");

        mockMvc.perform(get("/api/documents/sync/task-789"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"));
    }
}
