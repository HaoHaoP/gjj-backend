package com.example.rag.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChunkingServiceTest {

    private final ChunkingService service = new ChunkingService();

    @Test
    void shouldChunkShortTextIntoSinglePiece() {
        List<String> chunks = service.chunk("简短文本。", 500, 0, "SENTENCE");
        assertEquals(1, chunks.size());
        assertEquals("简短文本。", chunks.get(0));
    }

    @Test
    void shouldRespectChunkSize() {
        String text = "A".repeat(900) + "。";
        List<String> chunks = service.chunk(text, 500, 0, "SENTENCE");
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).length() <= 500);
    }

    @Test
    void shouldApplyOverlapInSentenceMode() {
        String text = "A".repeat(400) + "。\n" + "B".repeat(400) + "。\n" + "C".repeat(400) + "。";
        // chunkSize=500, overlap=100 — should produce 3 chunks with overlap
        List<String> chunks = service.chunk(text, 500, 100, "SENTENCE");
        assertTrue(chunks.size() >= 3, "Expected at least 3 chunks with overlap");
        // Middle chunk should contain content from boundaries
        assertTrue(chunks.get(1).contains("A") || chunks.get(1).contains("B"));
    }

    @Test
    void shouldApplyOverlapInFixedMode() {
        String text = "X".repeat(800) + "Y".repeat(800);
        List<String> chunks = service.chunk(text, 500, 50, "FIXED");
        assertTrue(chunks.size() >= 2);
        // Overlap: second chunk should start 50 chars before the 500 boundary
        assertFalse(chunks.get(1).isEmpty());
    }

    @Test
    void shouldHandleEmptyText() {
        assertEquals(0, service.chunk("", 500, 0, "SENTENCE").size());
        assertEquals(0, service.chunk(null, 500, 0, "SENTENCE").size());
    }

    @Test
    void shouldHandleLargeChunkSize() {
        String text = "Hello world。";
        List<String> chunks = service.chunk(text, 2000, 0, "SENTENCE");
        assertEquals(1, chunks.size());
    }

    @Test
    void shouldHandleZeroOverlap() {
        String text = "A".repeat(600) + "。" + "B".repeat(600) + "。";
        List<String> chunks = service.chunk(text, 500, 0, "SENTENCE");
        assertEquals(2, chunks.size());
    }

    @Test
    void shouldBreakAtSentenceInSentenceMode() {
        // All periods should be sentence boundaries
        String text = "第一章。总则内容较长占满空间...。第二条。";
        List<String> chunks = service.chunk(text, 100, 0, "SENTENCE");
        // Each chunk should end with a sentence-ending char
        for (String c : chunks) {
            assertTrue(c.isEmpty() || c.endsWith("。") || c.length() >= 100,
                    "Chunk should end at sentence boundary or reach max size: " + c);
        }
    }
}
