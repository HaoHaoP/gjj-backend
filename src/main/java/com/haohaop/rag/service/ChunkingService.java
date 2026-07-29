package com.haohaop.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ChunkingService {

    /** 匹配「第X章」「第X条」 */
    private static final Pattern CLAUSE_PATTERN =
            Pattern.compile("第[一二三四五六七八九十百千]+[章条]");

    public List<String> chunk(String text, int chunkSize, int overlapSize, String mode) {
        if (text == null || text.isBlank()) return List.of();

        List<String> result;
        if ("CLAUSE".equalsIgnoreCase(mode)) {
            result = clauseChunk(text);
        } else if ("FIXED".equalsIgnoreCase(mode)) {
            result = fixedChunk(text, chunkSize, overlapSize);
        } else {
            result = sentenceChunk(text, chunkSize, overlapSize);
        }

        if (result.isEmpty() && !text.isBlank()) {
            result = List.of(text.trim());
        }
        return result;
    }

    // ── CLAUSE 模式：按「第X条」/「第X章」切块 ──

    private List<String> clauseChunk(String text) {
        List<String> chunks = new ArrayList<>();
        Matcher m = CLAUSE_PATTERN.matcher(text);

        // 收集所有条款/章节标题的位置
        List<Integer> positions = new ArrayList<>();
        while (m.find()) {
            positions.add(m.start());
        }

        if (positions.isEmpty()) {
            chunks.add(text.trim());
            return chunks;
        }

        // 第一条标题之前的文字作为导语
        if (positions.get(0) > 0) {
            String preamble = text.substring(0, positions.get(0)).trim();
            if (!preamble.isEmpty()) chunks.add(preamble);
        }

        for (int i = 0; i < positions.size(); i++) {
            int start = positions.get(i);
            int end = (i + 1 < positions.size()) ? positions.get(i + 1) : text.length();
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) chunks.add(chunk);
        }

        log.debug("Clause chunk: {} chunks from {} chars", chunks.size(), text.length());
        return chunks;
    }

    // ── SENTENCE 模式 ──

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

    // ── FIXED 模式 ──

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
