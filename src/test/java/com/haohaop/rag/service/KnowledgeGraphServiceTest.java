package com.haohaop.rag.service;

import com.haohaop.rag.entity.ChunkEntity;
import com.haohaop.rag.entity.DocumentEntity;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.*;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KnowledgeGraphService")
class KnowledgeGraphServiceTest {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphServiceTest.class);

    private boolean neo4jCleared;
    private final List<String> policies = new ArrayList<>();
    private final List<String[]> clauses = new ArrayList<>();
    private final List<String[]> crossRefs = new ArrayList<>();
    private final List<String[]> concepts = new ArrayList<>();
    private final List<String[]> mentions = new ArrayList<>();
    private final Map<Integer, Map<String, Object>> dsResponses = new LinkedHashMap<>();
    private int dsCalls;

    private KnowledgeGraphService kgService;
    private DocumentEntity doc1, doc2;

    private static DocumentEntity doc(String id, String title) {
        return new DocumentEntity(id, title, "PIPELINE", 2, 500, 0, "CLAUSE", null, null, 0L);
    }

    private static ChunkEntity chunk(String cn, String text, String pt, String docId) {
        ChunkEntity c = new ChunkEntity();
        c.setDocumentId(docId); c.setChunkIndex(1); c.setText(text);
        c.setClauseNumber(cn); c.setParentTitle(pt); c.setCreatedAt(LocalDateTime.now());
        return c;
    }

    @BeforeEach void setUp() {
        neo4jCleared = false; policies.clear(); clauses.clear(); crossRefs.clear();
        concepts.clear(); mentions.clear(); dsResponses.clear(); dsCalls = 0;
        doc1 = doc("d1","南宁住房公积金提取管理办法");
        doc2 = doc("d2","南宁住房公积金贷款管理办法");

        Neo4jService n4j = new Neo4jService("bolt://l:7687","n","p") {
            @Override public void clearAll() { neo4jCleared = true; }
            @Override public void createPolicyNode(String id, String t, String s) { policies.add(t); }
            @Override public void createClauseNode(String pt, String cn, String tx, String cid) { clauses.add(new String[]{pt,cn}); }
            @Override public void createChapterNode(String pt, String cn, String ct) {}
            @Override public void createSectionNode(String pt, String cn, String sn) {}
            @Override public void linkClauseToSection(String pt, String cn, String sn, String cln) {}
            @Override public void createCrossReference(String fc, String rt, String tp, String ev) { crossRefs.add(new String[]{fc,rt,tp}); }
            @Override public void createConceptNode(String n, String d) { concepts.add(new String[]{n,d}); }
            @Override public void createMentionsRelation(String cn, String con, String ev) { mentions.add(new String[]{cn,con}); }
        };

        DeepSeekService ds = new DeepSeekService(null, null) {
            @Override public Map<String, Object> chatJson(String s, String m) {
                dsCalls++;
                return dsResponses.getOrDefault(dsCalls, Map.of("relations",List.of()));
            }
        };

        // DocRepo: add docs directly to the inherited "docs" field
        RepoStubs.DocRepoStub dr = new RepoStubs.DocRepoStub();
        dr.docs.add(doc1);
        dr.docs.add(doc2);

        // ChunkRepo: add chunks directly + override Pageable version
        RepoStubs.ChunkRepoStub cr = new RepoStubs.ChunkRepoStub() {
            @Override public Page<ChunkEntity> findByDocumentIdOrderByChunkIndexAsc(String id, Pageable p) {
                if ("d1".equals(id)) return new PageImpl<>(List.of(
                        chunk("第一条","为加强管理...",doc1.getTitle(),"d1"),
                        chunk("第二条","本办法适用于...",doc1.getTitle()+" > 第一章","d1"),
                        chunk("第三条","管理中心负责...",doc1.getTitle()+" > 第一章","d1")), p, 3);
                if ("d2".equals(id)) return new PageImpl<>(List.of(
                        chunk("第一条","为规范贷款...",doc2.getTitle(),"d2"),
                        chunk("第二条","参照《南宁住房公积金提取管理办法》...",doc2.getTitle(),"d2")), p, 2);
                return Page.empty();
            }
        };
        // Add concepts-testing chunk to findAll
        cr.chunks.add(chunk("第一条","缴存比例不得低于5%。贷款额度最高为60万元。",doc1.getTitle(),"d1"));

        kgService = new KnowledgeGraphService(n4j, ds, dr, cr);
        log.info("--- KG test setup done ---");
    }

    @Nested @DisplayName("buildAll success")
    class Success {
        @BeforeEach void setup() {
            dsResponses.put(1,Map.of("relations",List.of()));
            dsResponses.put(2,Map.of("relations",List.of(Map.of("fromClause","第二条","relation","REFERENCES","toDocument","南宁住房公积金提取管理办法","evidence","参照..."))));
            dsResponses.put(3,Map.of("concepts",List.of(Map.of("name","缴存比例","description","职工公积金缴存金额占工资的比例"),Map.of("name","贷款额度","description","最高可贷款金额上限"))));
        }

        @Test @DisplayName("clears Neo4j first")
        void clearsFirst() { log.info("[Test] clearFirst"); kgService.buildAll(pct -> {}); assertThat(neo4jCleared).isTrue(); log.info("[PASS] Neo4j cleared"); }

        @Test @DisplayName("creates Policy nodes (2)")
        void policies() { log.info("[Test] policies"); kgService.buildAll(pct -> {}); assertThat(policies).containsExactlyInAnyOrder(doc1.getTitle(),doc2.getTitle()); log.info("[PASS] {} policies",policies.size()); }

        @Test @DisplayName("creates Clause nodes (3+2=5)")
        void clauseNodes() { log.info("[Test] clauses"); kgService.buildAll(pct -> {}); assertThat(clauses).hasSize(5); log.info("[PASS] {} clauses",clauses.size()); }

        @Test @DisplayName("LLM extracts cross-references (1)")
        void crossRefsTest() { log.info("[Test] crossRefs"); kgService.buildAll(pct -> {}); assertThat(crossRefs).hasSize(1); assertThat(crossRefs.get(0)[0]).isEqualTo("第二条"); log.info("[PASS] {} refs, {} DS calls",crossRefs.size(),dsCalls); }

        @Test @DisplayName("LLM extracts concepts + keyword-match (2 concepts, 1 mention)")
        void conceptsTest() { log.info("[Test] concepts"); kgService.buildAll(pct -> {}); assertThat(concepts).hasSize(2); assertThat(concepts.get(0)[0]).isEqualTo("缴存比例"); log.info("[PASS] {} concepts",concepts.size()); }

        @Test @DisplayName("returns summary map")
        void summary() { log.info("[Test] summary"); Map<String,Object> r = kgService.buildAll(pct -> {}); assertThat(r).containsKeys("cleared","policies","crossReferences","concepts","elapsedMs"); assertThat(r.get("policies")).isEqualTo(2); log.info("[PASS] summary={}",r); }
    }

    @Nested @DisplayName("error handling")
    class Errors {
        @Test @DisplayName("concept failure → 0 concepts, no abort")
        void conceptFail() {
            log.info("[Test] conceptFailure");
            dsResponses.put(1,Map.of("relations",List.of()));
            dsResponses.put(2,Map.of("relations",List.of()));
            dsResponses.put(3,Map.of());
            Map<String,Object> r = kgService.buildAll(pct -> {});
            assertThat(r.get("concepts")).isEqualTo(0);
            assertThat(r.get("policies")).isEqualTo(2);
            log.info("[PASS] survived: concepts=0, policies=2");
        }
    }
}
