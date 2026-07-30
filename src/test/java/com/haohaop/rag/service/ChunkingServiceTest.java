package com.haohaop.rag.service;

import com.haohaop.rag.service.ChunkingService.ChunkSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ChunkingService")
class ChunkingServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ChunkingServiceTest.class);
    private final ChunkingService service = new ChunkingService();

    private static final String POLICY_TEXT = """
            南宁住房公积金提取管理办法

            第一章 总则

            第一条 为加强住房公积金提取管理，根据有关规定制定本办法。

            第二条 本办法适用于南宁市行政区域内住房公积金的提取管理。

            第三条 南宁住房公积金管理中心负责本市住房公积金的提取管理。

            第二章 提取条件

            第四条 职工有下列情形之一的，可以申请提取住房公积金，包括购买、建造、翻建、大修自住住房的，以及偿还购建自住住房贷款本息的。

            第五条 职工死亡或者被宣告死亡的，其继承人、受遗赠人可以提取职工住房公积金账户内的存储余额。

            第六章 法律责任

            第六条 管理中心及其工作人员滥用职权、玩忽职守、徇私舞弊的，依法给予处分。

            第七条 本办法自2024年1月1日起施行。
            """;

    /** 使用（一）/ 一、 作为章节层级的文本 */
    private static final String NUM_POLICY_TEXT = """
            某管理办法

            （一）总纲
            第一条 这是第一条内容。

            第二条 这是第二条内容。

            （二）具体规定
            一、提取条件
            第三条 职工可以申请提取。

            第四条 偿还贷款本息。
            二、法律责任
            第五条 违反规定将追究责任。

            第六条 本办法自颁布之日起施行。
            """;

    private static final String PLAIN_TEXT = """
            住房公积金是指国家机关、国有企业、城镇集体企业、外商投资企业、城镇私营企业及其他城镇企业、
            事业单位、民办非企业单位、社会团体及其在职职工缴存的长期住房储金。
            """;

    @Nested @DisplayName("chunkStructured CLAUSE mode")
    class ClauseStructured {

        @Test @DisplayName("finds at least 7 clause segments")
        void findsClauses() {
            log.info("[Test] findsClauses");
            List<ChunkSegment> segs = service.chunkStructured(POLICY_TEXT, 500, 0, "CLAUSE");
            assertThat(segs).hasSizeGreaterThanOrEqualTo(7); // 1 preamble + 7 articles
            ChunkSegment first = segs.stream().filter(s -> s.clauseNumber() != null).findFirst().orElseThrow();
            assertThat(first.clauseNumber()).isEqualTo("第一条");
            log.info("[PASS] {} segments, first clause='{}'", segs.size(), first.clauseNumber());
        }

        @Test @DisplayName("tracks chapter transitions")
        void tracksChapters() {
            log.info("[Test] tracksChapters");
            List<ChunkSegment> segs = service.chunkStructured(POLICY_TEXT, 500, 0, "CLAUSE");

            ChunkSegment a3 = segs.stream().filter(s -> "第三条".equals(s.clauseNumber())).findFirst().orElseThrow();
            assertThat(a3.chapterTitle()).isEqualTo("第一章");
            log.info("[PASS] art3 chapter='{}'", a3.chapterTitle());

            ChunkSegment a4 = segs.stream().filter(s -> "第四条".equals(s.clauseNumber())).findFirst().orElseThrow();
            assertThat(a4.chapterTitle()).isEqualTo("第二章");
            log.info("[PASS] art4 chapter='{}'", a4.chapterTitle());

            ChunkSegment a6 = segs.stream().filter(s -> "第六条".equals(s.clauseNumber())).findFirst().orElseThrow();
            assertThat(a6.chapterTitle()).isEqualTo("第六章");
            log.info("[PASS] art6 chapter='{}'", a6.chapterTitle());
        }

        @Test @DisplayName("preamble has null clauseNumber")
        void preambleNullClause() {
            log.info("[Test] preambleNullClause");
            List<ChunkSegment> segs = service.chunkStructured(POLICY_TEXT, 500, 0, "CLAUSE");
            ChunkSegment preamble = segs.get(0);
            assertThat(preamble.clauseNumber()).isNull();
            assertThat(preamble.text()).contains("南宁住房公积金提取管理办法");
            log.info("[PASS] preamble is first segment, no clause number");
        }

        @Test @DisplayName("clause text is complete")
        void clauseTextComplete() {
            log.info("[Test] clauseTextComplete");
            List<ChunkSegment> segs = service.chunkStructured(POLICY_TEXT, 500, 0, "CLAUSE");
            ChunkSegment a1 = segs.stream().filter(s -> "第一条".equals(s.clauseNumber())).findFirst().orElseThrow();
            assertThat(a1.text()).contains("为加强住房公积金提取管理");
            log.info("[PASS] art1 text is complete");
        }

        @Test @DisplayName("handles null/empty input")
        void handlesEmpty() {
            log.info("[Test] handlesEmpty");
            assertThat(service.chunkStructured(null, 500, 0, "CLAUSE")).isEmpty();
            assertThat(service.chunkStructured("", 500, 0, "CLAUSE")).isEmpty();
            assertThat(service.chunkStructured("   ", 500, 0, "CLAUSE")).isEmpty();
            log.info("[PASS] empty inputs return empty list");
        }

        @Test @DisplayName("plain text returns single segment")
        void plainTextSingle() {
            log.info("[Test] plainTextSingle");
            List<ChunkSegment> segs = service.chunkStructured(PLAIN_TEXT, 500, 0, "CLAUSE");
            assertThat(segs).hasSize(1);
            assertThat(segs.get(0).clauseNumber()).isNull();
            log.info("[PASS] plain text -> single segment");
        }

        @Test @DisplayName("tracks （一） as chapter and 一、 as section hierarchy")
        void tracksParenChapterEnumSection() {
            log.info("[Test] tracksParenChapterEnumSection");
            List<ChunkSegment> segs = service.chunkStructured(NUM_POLICY_TEXT, 500, 0, "CLAUSE");

            // Articles 1-2 should be under （一） chapter
            ChunkSegment a1 = segs.stream().filter(s -> "第一条".equals(s.clauseNumber())).findFirst().orElseThrow();
            assertThat(a1.chapterTitle()).isEqualTo("（一）");
            assertThat(a1.sectionTitle()).isNull();

            // Article 3 should be under （二） chapter, 一、 section
            ChunkSegment a3 = segs.stream().filter(s -> "第三条".equals(s.clauseNumber())).findFirst().orElseThrow();
            assertThat(a3.chapterTitle()).isEqualTo("（二）");
            assertThat(a3.sectionTitle()).isEqualTo("一、");

            // Article 4 should also be under （二） chapter, 一、 section
            ChunkSegment a4 = segs.stream().filter(s -> "第四条".equals(s.clauseNumber())).findFirst().orElseThrow();
            assertThat(a4.chapterTitle()).isEqualTo("（二）");
            assertThat(a4.sectionTitle()).isEqualTo("一、");

            // Article 5 should be under （二） chapter, 二、 section
            ChunkSegment a5 = segs.stream().filter(s -> "第五条".equals(s.clauseNumber())).findFirst().orElseThrow();
            assertThat(a5.chapterTitle()).isEqualTo("（二）");
            assertThat(a5.sectionTitle()).isEqualTo("二、");

            log.info("[PASS] paren chapter & enum section hierarchy tracking correct");
        }
    }

    @Nested @DisplayName("chunkStructured non-CLAUSE mode")
    class NonClauseMode {
        @Test @DisplayName("SENTENCE returns single segment")
        void sentenceSingle() {
            List<ChunkSegment> segs = service.chunkStructured(POLICY_TEXT, 500, 50, "SENTENCE");
            assertThat(segs).hasSize(1);
            assertThat(segs.get(0).clauseNumber()).isNull();
        }

        @Test @DisplayName("FIXED returns single segment")
        void fixedSingle() {
            List<ChunkSegment> segs = service.chunkStructured("Some text", 500, 0, "FIXED");
            assertThat(segs).hasSize(1);
            assertThat(segs.get(0).clauseNumber()).isNull();
        }
    }

    @Nested @DisplayName("chunk original (backward compat)")
    class OriginalChunk {
        @Test @DisplayName("CLAUSE mode works")
        void clauseMode() {
            List<String> chunks = service.chunk(POLICY_TEXT, 500, 0, "CLAUSE");
            assertThat(chunks).isNotEmpty();
            assertThat(chunks).anyMatch(c -> c.contains("第一条"));
        }

        @Test @DisplayName("SENTENCE mode works")
        void sentenceMode() {
            List<String> chunks = service.chunk("A short test. Another sentence.", 50, 5, "SENTENCE");
            assertThat(chunks).isNotEmpty();
        }
    }
}
