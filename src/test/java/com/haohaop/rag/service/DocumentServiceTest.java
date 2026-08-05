package com.haohaop.rag.service;

import com.haohaop.rag.entity.ChunkEntity;
import com.haohaop.rag.entity.DocumentEntity;
import com.haohaop.rag.service.ChunkingService.ChunkSegment;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DocumentService")
class DocumentServiceTest {
    private static final Logger log = LoggerFactory.getLogger(DocumentServiceTest.class);

    private RepoStubs.DocRepoStub docRepo;
    private RepoStubs.ChunkRepoStub chunkRepo;
    private final List<String> milvusIds = new ArrayList<>();

    private static final String TITLE = "南宁住房公积金提取管理办法";
    private static final ChunkSegment SEG1 = new ChunkSegment("第一条 测试内容...", "第一条", "第一章", null);
    private static final ChunkSegment SEG2 = new ChunkSegment("第二条 更多内容...", "第二条", "第一章", null);

    @BeforeEach void setUp() {
        docRepo = new RepoStubs.DocRepoStub();
        chunkRepo = new RepoStubs.ChunkRepoStub();
        milvusIds.clear();
        log.info("--- DocumentService test setup done ---");
    }

    private DocumentService build(List<ChunkSegment> segs) {
        ChunkingService cStub = new ChunkingService() {
            @Override public List<ChunkSegment> chunkStructured(String t, int cs, int os, String m) { return segs; }
        };
        EmbeddingService eStub = new EmbeddingService(null, null, null) {
            @Override public List<List<Float>> encode(List<String> ts) {
                List<List<Float>> r = new ArrayList<>();
                for (int i = 0; i < ts.size(); i++) r.add(List.of((float)i/100, 0.5f));
                return r;
            }
            @Override public List<List<Float>> encodeBatch(List<String> ts) {
                return encode(ts);
            }
        };
        MilvusService mStub = new MilvusService(null) {
            @Override public void insertChunks(String id, List<String> tls, List<String> txs, List<List<Float>> embs) {
                milvusIds.add(id);
            }
        };
        // KG service mock: no-op by default
        KnowledgeGraphService kgStub = new KnowledgeGraphService(null, null, docRepo, chunkRepo);
        return new DocumentService(cStub, eStub, mStub, docRepo, chunkRepo,
                new MinioService("http://localhost:9000", "dummy", "dummy", null) {},
                kgStub);
    }

    @Test @DisplayName("ingestWithMinio: returns documentId + chunk count")
    void returnsDocIdAndCount() {
        log.info("[Test] ingestWithMinio — return values");
        Map<String,Object> r = build(List.of(SEG1,SEG2)).ingest(TITLE,"x","PIPELINE",null,null,500,0,"CLAUSE");
        assertThat(r).containsKeys("documentId","chunks","title");
        assertThat(r.get("chunks")).isEqualTo(2);
        log.info("[PASS] documentId={}, chunks={}", r.get("documentId"), r.get("chunks"));
    }

    @Test @DisplayName("ingestWithMinio: saves DocumentEntity correctly")
    void savesDocument() {
        log.info("[Test] ingestWithMinio — doc table");
        build(List.of(SEG1,SEG2)).ingest(TITLE,"x","PIPELINE",null,null,500,0,"CLAUSE");
        assertThat(docRepo.docs).hasSize(1);
        DocumentEntity d = docRepo.docs.get(0);
        assertThat(d.getTitle()).isEqualTo(TITLE);
        assertThat(d.getSource()).isEqualTo("PIPELINE");
        assertThat(d.getChunkMode()).isEqualTo("CLAUSE");
        assertThat(d.getFileSize()).isGreaterThan(0);
        log.info("[PASS] doc saved: title='{}', chunks={}, fileSize={}", d.getTitle(), d.getChunkCount(), d.getFileSize());
    }

    @Test @DisplayName("ingestWithMinio: chunks have clauseNumber + parentTitle")
    void chunksHaveClauseMeta() {
        log.info("[Test] ingestWithMinio — chunk clause metadata");
        build(List.of(SEG1,SEG2)).ingest(TITLE,"x","PIPELINE",null,null,500,0,"CLAUSE");
        assertThat(chunkRepo.chunks).hasSize(2);
        assertThat(chunkRepo.chunks.get(0).getClauseNumber()).isEqualTo("第一条");
        assertThat(chunkRepo.chunks.get(0).getParentTitle()).contains(TITLE).contains("第一章");
        log.info("[PASS] chunk[0]: clause='{}', parent='{}'", chunkRepo.chunks.get(0).getClauseNumber(), chunkRepo.chunks.get(0).getParentTitle());
    }

    @Test @DisplayName("ingestWithMinio: calls Milvus insert")
    void callsMilvus() {
        log.info("[Test] ingestWithMinio — Milvus insert");
        build(List.of(SEG1,SEG2)).ingest(TITLE,"x","PIPELINE",null,null,500,0,"CLAUSE");
        assertThat(milvusIds).hasSize(1);
        log.info("[PASS] Milvus received 1 insert, docId='{}'", milvusIds.get(0));
    }

    @Test @DisplayName("empty content → chunks=0")
    void emptyContentZero() {
        log.info("[Test] ingestWithMinio — empty content");
        Map<String,Object> r = build(List.of()).ingest(TITLE,"","PIPELINE",null,null,500,0,"CLAUSE");
        assertThat(r.get("chunks")).isEqualTo(0);
        log.info("[PASS] returned 0 chunks");
    }

    @Test @DisplayName("parentTitle chains doc > chapter > section")
    void parentTitleWithSection() {
        log.info("[Test] parentTitle — section inclusion");
        ChunkSegment seg = new ChunkSegment("text","第十条","第三章","第二节");
        build(List.of(seg)).ingest(TITLE,"x","PIPELINE",null,null,500,0,"CLAUSE");
        assertThat(chunkRepo.chunks.get(0).getParentTitle()).isEqualTo(TITLE+" > 第三章 > 第二节");
        log.info("[PASS] parentTitle='{}'", chunkRepo.chunks.get(0).getParentTitle());
    }
}
