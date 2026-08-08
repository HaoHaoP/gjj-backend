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

    /** 带条款/层级元数据的结构化分块段。 */
    public record ChunkSegment(
            String text,
            String clauseNumber,
            String chapterTitle,
            String sectionTitle
    ) {}

    /** 匹配「第X章」「第X条」 */
    private static final Pattern CLAUSE_PATTERN =
            Pattern.compile("第[一二三四五六七八九十百千]+[章条]");

    public List<String> chunk(String text, int chunkSize, int overlapSize, String mode) {
        if (text == null || text.isBlank()) return List.of();

        List<String> result;
        if ("MARKDOWN".equalsIgnoreCase(mode)) {
            result = markdownChunk(text);
        } else if ("CLAUSE".equalsIgnoreCase(mode)) {
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

    /**
     * 结构化分块并提取条款级元数据。
     * CLAUSE 模式还会提取章/节/条编号。
     */
    public List<ChunkSegment> chunkStructured(String text, int chunkSize, int overlapSize, String mode) {
        if (text == null || text.isBlank()) return List.of();

        if ("MARKDOWN".equalsIgnoreCase(mode)) {
            return markdownChunkStructured(text);
        }
        if ("CLAUSE".equalsIgnoreCase(mode)) {
            return clauseChunkStructured(text);
        }
        return List.of(new ChunkSegment(text.trim(), null, null, null));
    }

    // ── CLAUSE 模式：按「第X条」/「第X章」切块 ──

    private List<String> clauseChunk(String text) {
        List<String> chunks = new ArrayList<>();
        Matcher m = CLAUSE_PATTERN.matcher(text);

        List<Integer> positions = new ArrayList<>();
        while (m.find()) {
            positions.add(m.start());
        }

        if (positions.isEmpty()) {
            chunks.add(text.trim());
            return chunks;
        }

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

        log.debug("条款分块：从 {} 个字符生成 {} 个分块", chunks.size(), text.length());
        return chunks;
    }

    // ── CLAUSE STRUCTURED 模式：提取章/节/条层级 ──

    private static final Pattern SECTION_PATTERN =
            Pattern.compile("第([一二三四五六七八九十百千]+[章节条])\\s*|（[一二三四五六七八九十]+）|[一二三四五六七八九十]+、");
    private static final Pattern CHAPTER_TITLE_PATTERN =
            Pattern.compile("第([一二三四五六七八九十百千]+)章\\s*(.+)");
    private static final Pattern SECTION_TITLE_PATTERN =
            Pattern.compile("第([一二三四五六七八九十百千]+)节\\s*(.+)");
    private static final Pattern CLAUSE_NUM_PATTERN =
            Pattern.compile("第([一二三四五六七八九十百千]+)条");
    private static final Pattern PAREN_CHAPTER_PATTERN =
            Pattern.compile("（([一二三四五六七八九十]+)）");
    private static final Pattern ENUM_SECTION_PATTERN =
            Pattern.compile("([一二三四五六七八九十]+)、");

    private List<ChunkSegment> clauseChunkStructured(String text) {
        List<ChunkSegment> segments = new ArrayList<>();

        Matcher m = SECTION_PATTERN.matcher(text);
        List<Integer> positions = new ArrayList<>();
        while (m.find()) {
            positions.add(m.start());
        }

        if (positions.isEmpty()) {
            segments.add(new ChunkSegment(text.trim(), null, null, null));
            return segments;
        }

        String currentChapter = null;
        String currentSection = null;

        if (positions.get(0) > 0) {
            String preamble = text.substring(0, positions.get(0)).trim();
            if (!preamble.isEmpty()) {
                segments.add(new ChunkSegment(preamble, null, null, null));
            }
        }

        for (int i = 0; i < positions.size(); i++) {
            int start = positions.get(i);
            int end = (i + 1 < positions.size()) ? positions.get(i + 1) : text.length();
            String block = text.substring(start, end).trim();

            Matcher chMatch = CHAPTER_TITLE_PATTERN.matcher(block);
            Matcher secMatch = SECTION_TITLE_PATTERN.matcher(block);
            Matcher clMatch = CLAUSE_NUM_PATTERN.matcher(block);

            Matcher parenChMatch = PAREN_CHAPTER_PATTERN.matcher(block);
            Matcher enumSecMatch = ENUM_SECTION_PATTERN.matcher(block);

            if (chMatch.find()) {
                // 将章标题作为带前序上下文的分块保留，然后更新当前章
                segments.add(new ChunkSegment(block, null, currentChapter, currentSection));
                currentChapter = "第" + chMatch.group(1) + "章";
                currentSection = null;
            } else if (secMatch.find()) {
                segments.add(new ChunkSegment(block, null, currentChapter, currentSection));
                currentSection = "第" + secMatch.group(1) + "节";
            } else if (parenChMatch.find()) {
                segments.add(new ChunkSegment(block, null, currentChapter, currentSection));
                currentChapter = "（" + parenChMatch.group(1) + "）";
                currentSection = null;
            } else if (enumSecMatch.find()) {
                segments.add(new ChunkSegment(block, null, currentChapter, currentSection));
                currentSection = enumSecMatch.group(1) + "、";
            } else if (clMatch.find()) {
                String clauseNum = "第" + clMatch.group(1) + "条";
                segments.add(new ChunkSegment(block, clauseNum, currentChapter, currentSection));
            }
        }

        log.debug("条款结构化分块：从 {} 个字符生成 {} 个分块", segments.size(), text.length());
        return segments;
    }


    // ── MARKDOWN 模式：按 # / ## / **第X条** 切块 ──

    private static final Pattern MD_HEADING = Pattern.compile("(?m)^#{1,2}\\s+.*$");
    private static final Pattern MD_CLAUSE = Pattern.compile("(?m)^\\*\\*第([一二三四五六七八九十百千]+)条\\*\\*");

    private List<ChunkSegment> markdownChunkStructured(String text) {
        // 检测：若没有 ## 标题，则回退到 CLAUSE 模式
        if (!Pattern.compile("(?m)^##\s+", Pattern.MULTILINE).matcher(text).find()) {
            log.debug("未找到『## 』标题，回退到 CLAUSE 模式");
            return clauseChunkStructured(text);
        }

        List<ChunkSegment> segments = new ArrayList<>();
        String[] sections = MD_HEADING.split(text);
        Matcher headingMatcher = MD_HEADING.matcher(text);

        List<String> headings = new ArrayList<>();
        while (headingMatcher.find()) {
            headings.add(headingMatcher.group().trim());
        }

        // 前言：第一个标题之前的文本
        if (sections.length > 0 && !sections[0].isBlank()) {
            segments.add(new ChunkSegment(sections[0].trim(), null, null, null));
        }

        String currentChapter = null;

        // 处理每个标题及其内容
        for (int i = 0; i < headings.size() && i + 1 < sections.length; i++) {
            String heading = headings.get(i);
            String body = sections[i + 1];  // sections[0] 是前言，sections[1] 是第一个标题之后的内容

            if (heading.startsWith("##")) {
                currentChapter = heading.substring(2).trim();
            }

            // 按 **第X条** 标记拆分正文
            Matcher clauseMatcher = MD_CLAUSE.matcher(body);
            List<Integer> clausePositions = new ArrayList<>();
            while (clauseMatcher.find()) {
                clausePositions.add(clauseMatcher.start());
            }

            if (clausePositions.isEmpty()) {
                // 没有条款标记——将整个正文作为一个分块
                String text2 = (heading + "\n" + body.trim()).trim();
                if (!text2.isBlank()) {
                    segments.add(new ChunkSegment(text2, null, currentChapter, null));
                }
            } else {
                // 添加标题行及第一个条款之前的文本
                if (clausePositions.get(0) > 0) {
                    String intro = (heading + "\n" + body.trim().substring(0, clausePositions.get(0))).trim();
                    if (!intro.isBlank()) {
                        segments.add(new ChunkSegment(intro, null, currentChapter, null));
                    }
                }

                // 在条款边界处拆分
                for (int j = 0; j < clausePositions.size(); j++) {
                    int start = clausePositions.get(j);
                    int end = (j + 1 < clausePositions.size()) ? clausePositions.get(j + 1) : body.length();
                    String clauseBlock = body.substring(start, end).trim();

                    Matcher cnMatcher = CLAUSE_NUM_PATTERN.matcher(clauseBlock);
                    String clauseNum = null;
                    if (cnMatcher.find()) {
                        clauseNum = "第" + cnMatcher.group(1) + "条";
                    }

                    if (!clauseBlock.isBlank()) {
                        segments.add(new ChunkSegment(clauseBlock, clauseNum, currentChapter, null));
                    }
                }
            }
        }

        log.debug("Markdown 分块：从 {} 个字符生成 {} 个分块", segments.size(), text.length());
        return segments;
    }

    private List<String> markdownChunk(String text) {
        List<ChunkSegment> segs = markdownChunkStructured(text);
        List<String> result = new ArrayList<>();
        for (ChunkSegment seg : segs) {
            result.add(seg.text());
        }
        return result;
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
        log.debug("按句分块：从 {} 个字符生成 {} 个分块", chunks.size(), text.length());
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
        log.debug("固定长度分块：{} 个分块", chunks.size());
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
