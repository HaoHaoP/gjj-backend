package com.example.rag.service;

import com.example.rag.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RAGServiceTest {

    @Mock private EmbeddingService embeddingService;
    @Mock private MilvusService milvusService;
    @Mock private DeepSeekService deepSeekService;
    @Mock private Neo4jService neo4jService;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RAGService(
                embeddingService, milvusService, deepSeekService, neo4jService,
                0.5, 0.6);
    }

    @Test
    void shouldRejectWhenNoSearchResults() {
        when(embeddingService.encode(anyList())).thenReturn(List.of(List.of(1.0f)));
        when(milvusService.searchSimilar(anyList(), eq(5))).thenReturn(List.of());

        QueryResponse response = ragService.query("南宁公积金可以用来炒股吗？");

        assertTrue(response.rejected());
        assertTrue(response.answer().contains("未找到明确规定"));
    }

    @Test
    void shouldRejectWhenTop1BelowThreshold() {
        SearchHit hit = new SearchHit(1L, "doc.pdf", "chunk text", 0.3);
        when(embeddingService.encode(anyList())).thenReturn(List.of(List.of(1.0f)));
        when(milvusService.searchSimilar(anyList(), eq(5))).thenReturn(List.of(hit));

        QueryResponse response = ragService.query("test question");

        assertTrue(response.rejected());
    }

    @Test
    void shouldAnswerNormallyWhenNoKgRelations() {
        SearchHit hit1 = new SearchHit(1L, "doc.pdf", "chunk text", 0.85);
        when(embeddingService.encode(anyList())).thenReturn(List.of(List.of(1.0f)));
        when(milvusService.searchSimilar(anyList(), eq(5))).thenReturn(List.of(hit1));
        when(neo4jService.findRelations(anyString())).thenReturn(List.of());
        when(deepSeekService.chat(anyString(), anyString())).thenReturn("根据[1]《doc.pdf》，答案是...");

        QueryResponse response = ragService.query("test question");

        assertFalse(response.rejected());
        assertEquals(1, response.sources().size());
        assertEquals("doc.pdf", response.sources().get(0).title());
    }

    @Test
    void shouldIncludeKgRelations() {
        SearchHit hit1 = new SearchHit(1L, "docA.pdf", "chunk A", 0.85);
        SearchHit hit2 = new SearchHit(2L, "docB.pdf", "chunk B", 0.70);
        KgRelation kgRel = new KgRelation("第五条", "REFERENCES", "管理条例");

        when(embeddingService.encode(anyList()))
                .thenReturn(List.of(List.of(1.0f)))
                .thenReturn(List.of(List.of(2.0f)));
        when(milvusService.searchSimilar(anyList(), eq(5)))
                .thenReturn(List.of(hit1, hit2));
        when(neo4jService.findRelations("docA.pdf")).thenReturn(List.of(kgRel));
        when(neo4jService.findRelations("docB.pdf")).thenReturn(List.of());
        when(milvusService.searchSimilar(anyList(), eq(3)))
                .thenReturn(List.of(new SearchHit(3L, "管理条例.pdf", "第十八条...", 0.80)));
        when(deepSeekService.chat(anyString(), anyString()))
                .thenReturn("根据[1]《docA.pdf》第五条引用[3]《管理条例》...");

        QueryResponse response = ragService.query("test question");

        assertFalse(response.rejected());
        assertFalse(response.kgRelations().isEmpty());
    }

    @Test
    void shouldDetectRejectionFromLlmResponse() {
        SearchHit hit = new SearchHit(1L, "doc.pdf", "chunk text", 0.85);
        when(embeddingService.encode(anyList())).thenReturn(List.of(List.of(1.0f)));
        when(milvusService.searchSimilar(anyList(), eq(5))).thenReturn(List.of(hit));
        when(neo4jService.findRelations(anyString())).thenReturn(List.of());
        when(deepSeekService.chat(anyString(), anyString()))
                .thenReturn("未找到相关政策，无法回答您的问题。");

        QueryResponse response = ragService.query("test question");

        assertTrue(response.rejected());
    }

    @Test
    void shouldPropagateExceptionFromNeo4j() {
        SearchHit hit = new SearchHit(1L, "doc.pdf", "chunk text", 0.85);
        when(embeddingService.encode(anyList())).thenReturn(List.of(List.of(1.0f)));
        when(milvusService.searchSimilar(anyList(), eq(5))).thenReturn(List.of(hit));
        when(neo4jService.findRelations(anyString()))
                .thenThrow(new RuntimeException("Neo4j connection refused"));

        assertThrows(RuntimeException.class, () -> ragService.query("test question"));
    }
}
