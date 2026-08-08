package com.haohaop.rag.service;

import com.haohaop.rag.entity.ChunkEntity;
import com.haohaop.rag.entity.DocumentEntity;
import com.haohaop.rag.repository.ChunkRepository;
import com.haohaop.rag.repository.DocumentRepository;
import org.springframework.data.domain.PageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责从入库文档构建知识图谱。
 *
 * 流水线：clearAll → buildHierarchy → extractCrossReferences → extractConcepts。
 */
@Slf4j
@Service
public class KnowledgeGraphService {

    private final Neo4jService neo4jService;
    private final DeepSeekService deepSeekService;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;

    private static final Pattern CHAPTER_TITLE_PATTERN =
            Pattern.compile("第([一二三四五六七八九十百千]+)章\\s*(.+)");
    private static final Pattern SECTION_TITLE_PATTERN =
            Pattern.compile("第([一二三四五六七八九十百千]+)节\\s*(.+)");

    // 批量失败阈值：若超过 30% 的文档抽取失败，则中止
    private static final double MAX_FAILURE_RATE = 0.3;

    public KnowledgeGraphService(Neo4jService neo4jService, DeepSeekService deepSeekService,
                                  DocumentRepository documentRepository, ChunkRepository chunkRepository) {
        this.neo4jService = neo4jService;
        this.deepSeekService = deepSeekService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
    }

    /**
     * 完整构建知识图谱：清空 → 层级 → 交叉引用 → 概念。
     * @return 包含统计数量的结果 Map
     */
    public Map<String, Object> buildAll(Consumer<Integer> progressCallback) {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();

        // 1. 清空已有图谱
        neo4jService.clearAll();
        progressCallback.accept(3);
        result.put("cleared", true);

        // 2. 构建政策、条款、章/节层级结构
        int policyCount = buildDocumentHierarchy();
        progressCallback.accept(15);
        result.put("policies", policyCount);

        // 3. 抽取跨文档引用关系（LLM）
        int refCount = extractCrossReferences(progressCallback);
        progressCallback.accept(80);
        result.put("crossReferences", refCount);

        // 4. 抽取概念实体（LLM）
        int conceptCount = extractConcepts();
        progressCallback.accept(97);
        result.put("concepts", conceptCount);

        long elapsed = System.currentTimeMillis() - start;
        result.put("elapsedMs", elapsed);
        log.info("知识图谱构建完成：{} 个政策、{} 个引用、{} 个概念，耗时 {}ms",
                policyCount, refCount, conceptCount, elapsed);
        return result;
    }

    /**
     * 删除与某文档关联的全部图谱节点。
     * 文档被删除时调用，以保证知识图谱同步。
     */
    public void deleteByDocumentId(String documentId) {
        try {
            neo4jService.deleteByDocumentId(documentId);
        } catch (Exception e) {
            log.warn("删除文档 {} 的 Neo4j 图谱失败：{}", documentId, e.getMessage());
        }
    }

    // ==================================================================
    // 步骤 A：文档层级结构（基于规则）
    // ==================================================================

    private int buildDocumentHierarchy() {
        List<DocumentEntity> docs = documentRepository.findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 500)).getContent();

        for (DocumentEntity doc : docs) {
            neo4jService.createPolicyNode(doc.getDocumentId(), doc.getTitle(), doc.getSource());

            // 获取该文档的全部分块，按索引排序
            var page = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(doc.getDocumentId(),
                    org.springframework.data.domain.PageRequest.of(0, 500));

            String currentChapter = null;
            String currentSection = null;

            for (ChunkEntity chunk : page.getContent()) {
                String clauseNum = chunk.getClauseNumber();
                String pt = chunk.getParentTitle();

                if (clauseNum == null) {
                    // MARKDOWN 模式下的章引言分块
                    // 可能出现在任何条款之前——从 parentTitle 提取章信息
                    if (pt != null) {
                        String[] parts = pt.split(" > ");
                        if (parts.length >= 2 && parts[1].startsWith("第") && parts[1].contains("章")) {
                            String chapterNum = parts[1];
                            String chapterTitle = parts[1];
                            Matcher chMatcher = CHAPTER_TITLE_PATTERN.matcher(parts[1]);
                            if (chMatcher.find()) {
                                chapterNum = "第" + chMatcher.group(1) + "章";
                            }
                            // 在追加文本前确保章节点已存在
                            if (!chapterNum.equals(currentChapter)) {
                                currentChapter = chapterNum;
                                currentSection = null;
                                neo4jService.createChapterNode(doc.getTitle(), chapterNum, chapterTitle);
                            }
                            neo4jService.appendChapterText(doc.getTitle(), currentChapter, chunk.getText());
                        }
                    }
                    continue;
                }

                // 从 parentTitle 构建章/节层级
                if (pt != null) {
                    String[] parts = pt.split(" > ");
                    if (parts.length >= 2 && parts[1].startsWith("第") && parts[1].contains("章")) {
                        String chapterNum = parts[1];
                        String chapterTitle = parts[1];
                        Matcher chMatcher = CHAPTER_TITLE_PATTERN.matcher(parts[1]);
                        if (chMatcher.find()) {
                            chapterNum = "第" + chMatcher.group(1) + "章";
                        }
                        if (!chapterNum.equals(currentChapter)) {
                            currentChapter = chapterNum;
                            currentSection = null;
                            neo4jService.createChapterNode(doc.getTitle(), chapterNum, chapterTitle);
                        }
                    }
                    if (parts.length >= 3 && parts[2].startsWith("第") && parts[2].endsWith("节")) {
                        String sectionNum = parts[2];
                        if (!sectionNum.equals(currentSection) && currentChapter != null) {
                            currentSection = sectionNum;
                            neo4jService.createSectionNode(doc.getTitle(), currentChapter, currentSection);
                        }
                    }
                }

                neo4jService.createClauseNode(doc.getTitle(), clauseNum,
                        chunk.getText(), String.valueOf(chunk.getId()));

                // 将条款关联到其所属章
                if (currentChapter != null) {
                    neo4jService.linkClauseToChapter(doc.getTitle(), currentChapter, clauseNum);
                }

                // 若存在所属节，则将条款关联到节
                if (currentSection != null && currentChapter != null) {
                    neo4jService.linkClauseToSection(doc.getTitle(), currentChapter, currentSection, clauseNum);
                }
            }
        }
        log.info("层级构建完成：{} 个政策", docs.size());
        return docs.size();
    }

    // ==================================================================
    // 步骤 B：跨文档引用关系（LLM，按文档处理）
    // ==================================================================

    private static final String CROSS_REF_SYSTEM_PROMPT = """
        你是南宁住房公积金政策的法规知识图谱构建助手。
        你的任务：从政策条款中抽取跨文档引用关系。

        针对每一条款，判断其是否引用其他政策文件，可参考以下模式：
        - "根据《XXX》"
        - "参照《XXX》"
        - "依据《XXX》"
        - "废止《XXX》"
        - "按《XXX》第X条"
        - "执行《XXX》有关规定"
        - "《XXX》已有规定的"
        - "按照《XXX》"

        同时识别关系类型：
        - REFERENCES：一般性引用
        - REVISES：修订或修改
        - ABOLISHES：废止或废除

        只返回合法的 JSON，不要任何解释或 markdown。
        输出格式：
        {"relations": [{"fromClause": "第三条", "relation": "REFERENCES", "toDocument": "南宁住房公积金提取管理办法", "evidence": "相关原文片段"}]}
        如果没有找到引用关系，返回 {"relations": []}
        """;

    private int extractCrossReferences(Consumer<Integer> progressCallback) {
        List<DocumentEntity> docs = documentRepository.findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 500)).getContent();

        Set<String> knownTitles = new LinkedHashSet<>();
        for (DocumentEntity doc : docs) knownTitles.add(doc.getTitle());
        String knownTitlesList = String.join("\n", knownTitles.stream().map(t -> "- " + t).toList());
        Map<String, String> titleNormalize = new HashMap<>();
        for (String t : knownTitles) {
            String normalized = t.replaceAll("[（(](?:试行|修订|暂行|\\d{4}).*$", "").trim();
            titleNormalize.put(normalized, t);
        }

        int total = docs.size();
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger totalRefs = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(0);

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(4);
        java.util.List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (DocumentEntity doc : docs) {
            futures.add(executor.submit(() -> {
                try {
                    var page = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(doc.getDocumentId(),
                            org.springframework.data.domain.PageRequest.of(0, 500));
                    StringBuilder clausesText = new StringBuilder();
                    for (ChunkEntity chunk : page.getContent()) {
                        if (chunk.getClauseNumber() != null) {
                            clausesText.append("[").append(chunk.getClauseNumber()).append("] ")
                                    .append(chunk.getText()).append("\n\n");
                        }
                    }
                    if (clausesText.isEmpty()) return;

                    String userMessage = String.format("""
                            文档：《%s》

                            已知政策文档（请与以下精确标题匹配）：
                            %s

                            待分析的条款：
                            %s
                            """, doc.getTitle(), knownTitlesList, clausesText.toString());

                    Map<String, Object> response = deepSeekService.chatJson(CROSS_REF_SYSTEM_PROMPT, userMessage);

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> relations =
                            (List<Map<String, Object>>) response.getOrDefault("relations", List.of());

                    for (Map<String, Object> rel : relations) {
                        String fromClause = (String) rel.get("fromClause");
                        String relationType = ((String) rel.getOrDefault("relation", "REFERENCES")).toUpperCase();
                        String toDocument = (String) rel.get("toDocument");
                        String evidence = (String) rel.getOrDefault("evidence", "");

                        if (fromClause == null || toDocument == null) continue;
                        String matchedTitle = fuzzyMatchTitle(toDocument, titleNormalize);
                        if (matchedTitle == null) {
                            log.debug("未找到交叉引用目标：'{}'", toDocument);
                            continue;
                        }
                        neo4jService.createCrossReference(fromClause, relationType, matchedTitle, evidence);
                        totalRefs.incrementAndGet();
                    }
                    log.info("已为『{}』抽取交叉引用：{} 条关系", doc.getTitle(), relations.size());

                } catch (Exception e) {
                    failures.incrementAndGet();
                    log.warn("『{}』交叉引用抽取失败：{}", doc.getTitle(), e.getMessage());
                }
                completed.incrementAndGet();
                progressCallback.accept(20 + 60 * completed.get() / total);
            }));
        }

        executor.shutdown();
        for (var f : futures) {
            try { f.get(120, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
        }

        double failureRate = (double) failures.get() / docs.size();
        if (failureRate > MAX_FAILURE_RATE && docs.size() > 3) {
            log.error("交叉引用失败率 {} > {} —— 中止", failureRate, MAX_FAILURE_RATE);
            throw new RuntimeException("知识图谱交叉引用抽取失败：" + failures.get() + "/" + docs.size());
        }
        return totalRefs.get();
    }

    // ==================================================================
    // 步骤 C：概念实体（LLM，全局）
    // ==================================================================

    private static final String CONCEPT_SYSTEM_PROMPT = """
        你是南宁住房公积金政策的法规知识图谱构建助手。
        你的任务：从政策文档中识别 10-20 个核心领域概念。

        “概念”是指在多个政策和条款中反复出现的关键领域术语或实体。
        示例：缴存比例、贷款额度、提取条件、首付比例、商转公贷、还款方式。

        为每个概念提供：
        - name：简洁的中文术语（2-8 个字）
        - description：用一句话说明该概念在语境中的含义

        只返回合法的 JSON：
        {"concepts": [{"name": "...", "description": "..."}]}
        至少返回 10 个概念。如果找不到 10 个，请尽量多创建。
        """;

    private int extractConcepts() {
        List<DocumentEntity> docs = documentRepository.findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 500)).getContent();

        // 构建代表性摘要：每个政策标题 + 带条款编号的前 3 条
        StringBuilder summary = new StringBuilder();
        for (DocumentEntity doc : docs) {
            summary.append("■ 《").append(doc.getTitle()).append("》\n");
            var page = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(doc.getDocumentId(),
                    org.springframework.data.domain.PageRequest.of(0, 3));
            int count = 0;
            for (ChunkEntity chunk : page.getContent()) {
                if (count >= 3) break;
                String prefix = chunk.getClauseNumber() != null ? "  " + chunk.getClauseNumber() + ": " : "  ";
                summary.append(prefix).append(abbreviate(chunk.getText(), 150)).append("\n");
                count++;
            }
            summary.append("\n");
        }

        try {
            Map<String, Object> response = deepSeekService.chatJson(CONCEPT_SYSTEM_PROMPT, summary.toString());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> concepts =
                    (List<Map<String, Object>>) response.getOrDefault("concepts", List.of());

            for (Map<String, Object> concept : concepts) {
                String name = (String) concept.get("name");
                String description = (String) concept.getOrDefault("description", "");
                if (name == null || name.isBlank()) continue;
                neo4jService.createConceptNode(name.trim(), description);
            }

            // 将概念关联到条款：对每个概念，检索包含该概念的文本分块
            linkConceptsToClauses(concepts);

            log.info("已抽取 {} 个概念", concepts.size());
            return concepts.size();

        } catch (Exception e) {
            log.warn("概念抽取失败：{}", e.getMessage());
            return 0;
        }
    }

    private void linkConceptsToClauses(List<Map<String, Object>> concepts) {
        for (Map<String, Object> concept : concepts) {
            String name = (String) concept.get("name");
            if (name == null || name.isBlank()) continue;
            List<ChunkEntity> matches = chunkRepository.findTop10ByTextContaining(
                    name, PageRequest.of(0, 10));

            int linked = 0;
            for (ChunkEntity chunk : matches) {
                if (chunk.getText() == null) continue;

                if (chunk.getClauseNumber() != null) {
                    neo4jService.createMentionsRelation(chunk.getClauseNumber(), name, null);
                    linked++;
                } else if (chunk.getParentTitle() != null) {
                    // 章引言分块——将概念关联到章节点
                    String[] parts = chunk.getParentTitle().split(" > ");
                    if (parts.length >= 2 && parts[1].startsWith("第") && parts[1].contains("章")) {
                        Matcher chM = CHAPTER_TITLE_PATTERN.matcher(parts[1]);
                        if (chM.find()) {
                            String chNum = "第" + chM.group(1) + "章";
                            neo4jService.createChapterMentionsRelation(parts[0], chNum, name);
                            linked++;
                        }
                    }
                }
                if (linked >= 5) break; // 每个概念最多关联 5 处
            }
        }
    }

    // ==================================================================
    // 辅助方法
    // ==================================================================

    /**
     * 将可能被缩写或变体的标题与已知政策标题进行模糊匹配。
     */
    private String fuzzyMatchTitle(String raw, Map<String, String> normalizeMap) {
        if (raw == null) return null;

        // 先尝试精确匹配
        if (normalizeMap.containsKey(raw)) return normalizeMap.get(raw);

        // 尝试规范化匹配
        String normalized = raw.replaceAll("[（(](?:试行|修订|暂行|\\d{4}).*$", "").trim();
        for (Map.Entry<String, String> e : normalizeMap.entrySet()) {
            if (e.getKey().equals(normalized) || e.getKey().contains(normalized) || normalized.contains(e.getKey())) {
                return e.getValue();
            }
        }

        // 尝试子串匹配：raw 是任一已知标题的子串或反之
        for (Map.Entry<String, String> e : normalizeMap.entrySet()) {
            if (e.getValue().contains(raw) || raw.contains(e.getValue())) {
                return e.getValue();
            }
        }

        return null;
    }

    private String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
