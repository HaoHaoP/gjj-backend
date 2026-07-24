package com.example.rag.service;

import com.example.rag.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RAGService {

    private static final int TOP_K = 5;
    private static final int KG_INPUT_LIMIT = 3;
    private static final int KG_RETRIEVAL_TOP_K = 3;

    // LLM 拒答关键词正则
    private static final Pattern REJECT_PATTERN = Pattern.compile(
            "未找到相关|无法回答|不在.*范围|没有.*规定|无法.*判断");

    private static final String SYSTEM_PROMPT = """
            你是南宁住房公积金政策问答助手，专门为普通市民解答公积金政策问题。
            
            你的核心任务是把晦涩的政策条文翻译成老百姓听得懂的话。请严格遵循以下规则：

            1. 【通俗化改写】
               - 把"缴存基数""应缴存额"等术语替换为"每月按多少工资交""每月交多少钱"
               - 把"借款申请人""缴存人"统一称为"你"或"您"
               - 把"自住住房""购建住房"说成"自己住的房子""买房或建房"
               - 长句拆成短句，每个短句只讲一件事

            2. 【结构化呈现】
               - 用**加粗**标出关键数字和条件
               - 有多个条件的用编号列表
               - 金额信息单独列一行

            3. 【补充说明】
               - 如果政策有例外情况，主动提醒
               - 如果有容易踩坑的地方，用"⚠️ 注意"标出
               - 结尾补充办理渠道（线上/线下）或咨询电话 12329（如适用）

            4. 【引用规范】
               - 回答中使用 [1][2] 标记引用具体条文
               - 如：「根据[1]，你需要...」

            5. 【拒答规则】
               - 如果提供的资料无法回答问题，回复「未找到相关政策」
               - 严禁编造资料中未出现的信息
            """;

    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final DeepSeekService deepSeekService;
    private final Neo4jService neo4jService;

    private final double similarityThreshold;
    private final double kgLookupThreshold;

    public RAGService(EmbeddingService embeddingService,
                      MilvusService milvusService,
                      DeepSeekService deepSeekService,
                      Neo4jService neo4jService,
                      @Value("${rag.similarity-threshold}") double similarityThreshold,
                      @Value("${rag.kg-lookup-threshold}") double kgLookupThreshold) {
        this.embeddingService = embeddingService;
        this.milvusService = milvusService;
        this.deepSeekService = deepSeekService;
        this.neo4jService = neo4jService;
        this.similarityThreshold = similarityThreshold;
        this.kgLookupThreshold = kgLookupThreshold;
    }

    public QueryResponse query(String question) {
        // Step 1: 向量检索
        List<List<Float>> embeddings = embeddingService.encode(List.of(question));
        List<Float> queryEmbedding = embeddings.get(0);
        List<SearchHit> similarDocs = milvusService.searchSimilar(queryEmbedding, TOP_K);

        // Step 2: 陷阱题判断 — Top-1 阈值过滤
        if (similarDocs.isEmpty() || similarDocs.get(0).score() < similarityThreshold) {
            log.info("Trap question rejected (max score: {})",
                    similarDocs.isEmpty() ? 0 : similarDocs.get(0).score());
            return new QueryResponse(
                "您咨询的问题在南宁住房公积金现行政策中未找到明确规定。建议您核实问题后重新提问，或拨打南宁公积金服务热线 12329 咨询。",
                similarDocs.stream()
                        .map(doc -> new SourceInfo(doc.id(), doc.title(), doc.chunkText(), doc.score()))
                        .toList(),
                true, List.of()
            );
        }

        // Step 3: KG 查引用关系（仅对达标 chunk）
        List<KgRelation> kgRelations = new ArrayList<>();
        Set<String> kgDocTitles = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(KG_INPUT_LIMIT, similarDocs.size()); i++) {
            SearchHit doc = similarDocs.get(i);
            if (doc.score() < kgLookupThreshold) continue;
            List<KgRelation> rels = neo4jService.findRelations(doc.title());
            for (KgRelation r : rels) {
                kgRelations.add(r);
                kgDocTitles.add(r.toDocument());
            }
        }

        // Step 4: KG 回检 — 去重后查 Milvus 拿引用文档原文
        Set<String> existingTitles = similarDocs.stream()
                .map(SearchHit::title).collect(Collectors.toSet());
        List<SearchHit> kgHits = new ArrayList<>();
        for (String title : kgDocTitles) {
            if (existingTitles.contains(title)) continue;
            List<List<Float>> kgEmb = embeddingService.encode(List.of(title));
            List<SearchHit> hits = milvusService.searchSimilar(kgEmb.get(0), KG_RETRIEVAL_TOP_K);
            for (SearchHit hit : hits) {
                if (!existingTitles.contains(hit.title())) {
                    kgHits.add(hit);
                    existingTitles.add(hit.title());
                }
            }
        }

        // Step 5: 构建带序号引用的上下文（A 类在前，B 类去重后追加）
        List<String> numberedSources = new ArrayList<>();
        List<SourceInfo> sourceInfoList = new ArrayList<>();
        int idx = 1;
        for (SearchHit doc : similarDocs) {
            numberedSources.add(String.format("[%d] 《%s》 %s", idx, doc.title(), doc.chunkText()));
            sourceInfoList.add(new SourceInfo(doc.id(), doc.title(), doc.chunkText(), doc.score()));
            idx++;
        }
        for (SearchHit doc : kgHits) {
            numberedSources.add(String.format("[%d] 《%s》 %s", idx, doc.title(), doc.chunkText()));
            sourceInfoList.add(new SourceInfo(doc.id(), doc.title(), doc.chunkText(), doc.score()));
            idx++;
        }

        // 追加 KG 引用关系描述
        if (!kgRelations.isEmpty()) {
            numberedSources.add("\n【政策引用关系】");
            for (KgRelation r : kgRelations) {
                numberedSources.add(String.format(
                        "- %s %s → 《%s》", r.fromClause(), r.relation(), r.toDocument()
                ));
            }
        }
        String context = String.join("\n", numberedSources);

        // Step 6: LLM 生成（中文 prompt，要求 [1][2] 引用）
        String userMessage = "政策条文：\n" + context + "\n\n用户问题：" + question;
        String answer = deepSeekService.chat(SYSTEM_PROMPT, userMessage);

        // Step 7: LLM 二次判断 — 正则匹配拒答关键词
        boolean rejected = REJECT_PATTERN.matcher(answer).find();

        // Step 8: 引用编号校验
        int totalSources = sourceInfoList.size();
        validateCitations(answer, totalSources);

        log.info("RAG query: {} sources, {} KG relations, rejected={}",
                sourceInfoList.size(), kgRelations.size(), rejected);

        return new QueryResponse(answer, sourceInfoList, rejected, kgRelations);
    }

    private void validateCitations(String answer, int totalSources) {
        java.util.regex.Matcher m = Pattern.compile("\\[(\\d+)\\]").matcher(answer);
        while (m.find()) {
            int n = Integer.parseInt(m.group(1));
            if (n > totalSources) {
                log.warn("Citation [{}] exceeds source count ({})", n, totalSources);
            }
        }
    }
}
