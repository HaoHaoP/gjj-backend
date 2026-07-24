package com.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ChunkingService {

    private static final int CHUNK_SIZE = 500;

    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            // Try to break at a sentence boundary near the end
            if (end < text.length()) {
                int breakPoint = findBreakPoint(text, start, end);
                chunks.add(text.substring(start, breakPoint).trim());
                start = breakPoint;
            } else {
                chunks.add(text.substring(start).trim());
                break;
            }
        }

        log.debug("Split text into {} chunks", chunks.size());
        return chunks;
    }

    private int findBreakPoint(String text, int start, int preferredEnd) {
        int end = preferredEnd;
        // Look backwards for sentence-ending punctuation
        for (int i = end; i > start; i--) {
            char c = text.charAt(i - 1);
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                return i;
            }
        }
        // Fall back to whitespace
        for (int i = end; i > start; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return i;
            }
        }
        return end;
    }
}
