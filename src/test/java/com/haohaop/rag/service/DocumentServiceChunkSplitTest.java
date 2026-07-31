package com.haohaop.rag.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentServiceChunkSplitTest {

    private static final int MAX_CHUNK_BYTES = 4096;

    @Test
    void longChineseTextShouldBeSplitToMaxBytes() {
        String text = "测试内容".repeat(1700);
        ChunkingService cs = new ChunkingService();
        List<ChunkingService.ChunkSegment> segs = splitLongSegments(
                cs.chunkStructured(text, 500, 0, "SENTENCE"));
        for (ChunkingService.ChunkSegment seg : segs) {
            int bytes = seg.text().getBytes(StandardCharsets.UTF_8).length;
            assertTrue(bytes <= MAX_CHUNK_BYTES,
                    "Chunk exceeds " + MAX_CHUNK_BYTES + " bytes: " + bytes);
        }
        assertTrue(segs.size() >= 2, "Expected >=2 chunks for long text");
    }

    private static List<ChunkingService.ChunkSegment> splitLongSegments(
            List<ChunkingService.ChunkSegment> raw) {
        List<ChunkingService.ChunkSegment> out = new ArrayList<>();
        for (ChunkingService.ChunkSegment seg : raw) {
            if (seg.text().getBytes(StandardCharsets.UTF_8).length <= MAX_CHUNK_BYTES) {
                out.add(seg);
                continue;
            }
            for (String p : splitAtItems(seg.text())) {
                out.add(new ChunkingService.ChunkSegment(
                        p, seg.clauseNumber(), seg.chapterTitle(), seg.sectionTitle()));
            }
        }
        return out;
    }

    private static final Pattern ITEM_PATTERN =
            Pattern.compile("([（(][一二三四五六七八九十百千]+[）)]|\\d+[、。．])");

    private static List<String> splitAtItems(String text) {
        List<String> parts = new ArrayList<>();
        Matcher m = ITEM_PATTERN.matcher(text);
        List<Integer> pos = new ArrayList<>();
        while (m.find()) pos.add(m.start());
        if (pos.size() >= 2) {
            if (pos.get(0) > 0) parts.add(text.substring(0, pos.get(0)).trim());
            for (int i = 0; i < pos.size(); i++) {
                int s = pos.get(i), e = i + 1 < pos.size() ? pos.get(i + 1) : text.length();
                String p = text.substring(s, e).trim();
                if (!p.isEmpty()) parts.add(p);
            }
        } else {
            parts = splitAtSentences(text);
        }
        List<String> finalParts = new ArrayList<>();
        for (String p : parts) {
            if (p.getBytes(StandardCharsets.UTF_8).length > MAX_CHUNK_BYTES) {
                finalParts.add(DocumentService.truncateUtf8(p, MAX_CHUNK_BYTES));
            } else {
                finalParts.add(p);
            }
        }
        return finalParts;
    }

    private static List<String> splitAtSentences(String text) {
        List<String> result = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int bufBytes = 0;
        int third = MAX_CHUNK_BYTES / 3, half = MAX_CHUNK_BYTES / 2;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            int added = DocumentService.utf8CharBytes(c, text, i);
            buf.append(c);
            if (added == 4) { i++; buf.append(text.charAt(i)); }
            bufBytes += added;
            i++;
            boolean end = (c == '。' || c == '！' || c == '？' || c == '\n') && bufBytes > third;
            if (end && bufBytes >= half) {
                result.add(buf.toString().trim());
                buf.setLength(0);
                bufBytes = 0;
            } else if (bufBytes >= MAX_CHUNK_BYTES) {
                int splitAt = findLastSentenceEnd(buf);
                if (splitAt > 0) {
                    result.add(buf.substring(0, splitAt).trim());
                    buf.replace(0, buf.length(), buf.substring(splitAt));
                    bufBytes = DocumentService.countUtf8Bytes(buf);
                } else {
                    result.add(buf.toString().trim());
                    buf.setLength(0);
                    bufBytes = 0;
                }
            }
        }
        if (!buf.isEmpty()) result.add(buf.toString().trim());
        return result.size() <= 1 ? List.of(text) : result;
    }

    private static int findLastSentenceEnd(CharSequence buf) {
        for (int j = buf.length() - 1; j >= 0; j--) {
            char c = buf.charAt(j);
            if (c == '。' || c == '！' || c == '？' || c == '\n') return j + 1;
        }
        return -1;
    }
}
