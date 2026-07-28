package com.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class ChunkingService {

    public List<String> chunk(String text, int chunkSize, int overlapSize, String mode) {
        if ("FIXED".equalsIgnoreCase(mode)) {
            return fixedChunk(text, chunkSize, overlapSize);
        }
        return sentenceChunk(text, chunkSize, overlapSize);
    }

    private List<String> sentenceChunk(String text, int chunkSize, int overlapSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            if (end < text.length()) {
                int breakPoint = findSentenceBreak(text, start, end);
                chunks.add(text.substring(start, breakPoint).trim());
                start = Math.max(start, breakPoint - overlapSize);
            } else {
                chunks.add(text.substring(start).trim());
                break;
            }
        }
        log.debug("Sentence chunk: {} chunks from {} chars", chunks.size(), text.length());
        return chunks;
    }

    private List<String> fixedChunk(String text, int chunkSize, int overlapSize) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) return chunks;

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end).trim());
            start = Math.max(start, end - overlapSize);
            if (start >= text.length()) break;
        }
        log.debug("Fixed chunk: {} chunks", chunks.size());
        return chunks;
    }

    private int findSentenceBreak(String text, int start, int preferredEnd) {
        for (int i = preferredEnd; i > start; i--) {
            char c = text.charAt(i - 1);
            if (c == '.' || c == '!' || c == '?' || c == '\n') return i;
        }
        for (int i = preferredEnd; i > start; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) return i;
        }
        return preferredEnd;
    }
}
