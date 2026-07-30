package com.haohaop.rag.service;

import com.haohaop.rag.entity.ChunkEntity;
import com.haohaop.rag.entity.DocumentEntity;
import com.haohaop.rag.repository.ChunkRepository;
import com.haohaop.rag.repository.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates knowledge graph construction from ingested documents.
 *
 * Pipeline: clearAll → buildHierarchy → extractCrossReferences → extractConcepts.
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

    // Batch failure threshold: if > 30% of docs fail extraction, abort
    private static final double MAX_FAILURE_RATE = 0.3;

    public KnowledgeGraphService(Neo4jService neo4jService, DeepSeekService deepSeekService,
                                  DocumentRepository documentRepository, ChunkRepository chunkRepository) {
        this.neo4jService = neo4jService;
        this.deepSeekService = deepSeekService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
    }

    /**
     * Full KG build: clear → hierarchy → cross-refs → concepts.
     * @return summary map with counts
     */
    public Map<String, Object> buildAll() {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();

        // 1. Clear existing graph
        neo4jService.clearAll();
        result.put("cleared", true);

        // 2. Build Policy + Clause + Chapter/Section hierarchy
        int policyCount = buildDocumentHierarchy();
        result.put("policies", policyCount);

        // 3. Extract cross-document references (LLM)
        int refCount = extractCrossReferences();
        result.put("crossReferences", refCount);

        // 4. Extract concept entities (LLM)
        int conceptCount = extractConcepts();
        result.put("concepts", conceptCount);

        long elapsed = System.currentTimeMillis() - start;
        result.put("elapsedMs", elapsed);
        log.info("KG build complete: {} policies, {} refs, {} concepts in {}ms",
                policyCount, refCount, conceptCount, elapsed);
        return result;
    }

    /**
     * Delete all graph nodes associated with a document.
     * Called when a document is deleted so the KG stays in sync.
     */
    public void deleteByDocumentId(String documentId) {
        try {
            neo4jService.deleteByDocumentId(documentId);
        } catch (Exception e) {
            log.warn("Neo4j delete for document {} failed: {}", documentId, e.getMessage());
        }
    }

    // ==================================================================
    // Step A: Document Hierarchy (Rule-based)
    // ==================================================================

    private int buildDocumentHierarchy() {
        List<DocumentEntity> docs = documentRepository.findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 500)).getContent();

        for (DocumentEntity doc : docs) {
            neo4jService.createPolicyNode(doc.getDocumentId(), doc.getTitle(), doc.getSource());

            // Get all chunks for this document, ordered by index
            var page = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(doc.getDocumentId(),
                    org.springframework.data.domain.PageRequest.of(0, 500));

            String currentChapter = null;
            String currentSection = null;

            for (ChunkEntity chunk : page.getContent()) {
                String clauseNum = chunk.getClauseNumber();
                String pt = chunk.getParentTitle();

                if (clauseNum == null) {
                    // Chapter intro chunk in MARKDOWN mode
                    // May appear before any clause — extract chapter from parentTitle
                    if (pt != null) {
                        String[] parts = pt.split(" > ");
                        if (parts.length >= 2 && parts[1].startsWith("第") && parts[1].contains("章")) {
                            String chapterNum = parts[1];
                            String chapterTitle = parts[1];
                            Matcher chMatcher = CHAPTER_TITLE_PATTERN.matcher(parts[1]);
                            if (chMatcher.find()) {
                                chapterNum = "第" + chMatcher.group(1) + "章";
                            }
                            // Ensure Chapter node exists before appending text
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

                // Build chapter/section hierarchy from parentTitle
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

                // Link clause to its parent Chapter
                if (currentChapter != null) {
                    neo4jService.linkClauseToChapter(doc.getTitle(), currentChapter, clauseNum);
                }

                // Link clause to section if applicable
                if (currentSection != null && currentChapter != null) {
                    neo4jService.linkClauseToSection(doc.getTitle(), currentChapter, currentSection, clauseNum);
                }
            }
        }
        log.info("Built hierarchy: {} policies", docs.size());
        return docs.size();
    }

    // ==================================================================
    // Step B: Cross-Document References (LLM, per-document)
    // ==================================================================

    private static final String CROSS_REF_SYSTEM_PROMPT = """
        You are a legal policy knowledge graph builder for Nanning Housing Provident Fund policies.
        Your task: extract cross-document references from policy clauses.

        For each clause, identify if it references another policy document using patterns like:
        - "根据《XXX》" (based on XXX)
        - "参照《XXX》" (refer to XXX)
        - "依据《XXX》" (according to XXX)
        - "废止《XXX》" (abolish XXX)
        - "按《XXX》第X条" (per XXX Article X)
        - "执行《XXX》有关规定" (follow XXX regulations)
        - "《XXX》已有规定的" (as stipulated in XXX)
        - "按照《XXX》" (in accordance with XXX)

        Also identify relationship types:
        - REFERENCES: general reference
        - REVISES: amendment or revision
        - ABOLISHES: repeal or abolishment

        Return ONLY valid JSON. No explanations, no markdown.
        Output format:
        {"relations": [{"fromClause": "第三条", "relation": "REFERENCES", "toDocument": "南宁住房公积金提取管理办法", "evidence": "relevant text snippet"}]}
        If no references found, return {"relations": []}
        """;

    private int extractCrossReferences() {
        List<DocumentEntity> docs = documentRepository.findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 500)).getContent();

        // Collect all known policy titles for the LLM to reference
        Set<String> knownTitles = new LinkedHashSet<>();
        for (DocumentEntity doc : docs) {
            knownTitles.add(doc.getTitle());
        }
        String knownTitlesList = String.join("\n", knownTitles.stream().map(t -> "- " + t).toList());

        // Normalize: strip version suffixes for matching
        Map<String, String> titleNormalize = new HashMap<>();
        for (String t : knownTitles) {
            String normalized = t.replaceAll("[（(](?:试行|修订|暂行|\\d{4}).*$", "").trim();
            titleNormalize.put(normalized, t);
        }

        int totalRefs = 0;
        int failures = 0;

        for (DocumentEntity doc : docs) {
            try {
                var page = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(doc.getDocumentId(),
                        org.springframework.data.domain.PageRequest.of(0, 500));

                // Build clauses payload
                StringBuilder clausesText = new StringBuilder();
                List<String> clauseNums = new ArrayList<>();
                for (ChunkEntity chunk : page.getContent()) {
                    if (chunk.getClauseNumber() != null) {
                        clausesText.append("[").append(chunk.getClauseNumber()).append("] ")
                                .append(chunk.getText()).append("\n\n");
                        clauseNums.add(chunk.getClauseNumber());
                    }
                }
                if (clauseNums.isEmpty()) continue;

                String userMessage = String.format("""
                    Document: 《%s》

                    Known policy documents (match against these exact titles):
                    %s

                    Clauses to analyze:
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

                    // Normalize target document title
                    String matchedTitle = fuzzyMatchTitle(toDocument, titleNormalize);
                    if (matchedTitle == null) {
                        log.debug("Cross-ref target not found: '{}'", toDocument);
                        continue;
                    }

                    neo4jService.createCrossReference(fromClause, relationType, matchedTitle, evidence);
                    totalRefs++;
                }
                log.info("Cross-ref extracted for '{}': {} relations", doc.getTitle(), relations.size());

            } catch (Exception e) {
                failures++;
                log.warn("Cross-ref extraction failed for '{}': {}", doc.getTitle(), e.getMessage());
            }
        }

        double failureRate = (double) failures / docs.size();
        if (failureRate > MAX_FAILURE_RATE && docs.size() > 3) {
            log.error("Cross-ref failure rate {} > {} — aborting", failureRate, MAX_FAILURE_RATE);
            throw new RuntimeException("KG cross-ref extraction failed: " + failures + "/" + docs.size());
        }
        return totalRefs;
    }

    // ==================================================================
    // Step C: Concept Entities (LLM, global)
    // ==================================================================

    private static final String CONCEPT_SYSTEM_PROMPT = """
        You are a legal policy knowledge graph builder for Nanning Housing Provident Fund policies.
        Your task: identify 10-20 core domain concepts from the policy documents.

        A "concept" is a key domain term or entity that appears across multiple policies and clauses.
        Examples: 缴存比例 (contribution ratio), 贷款额度 (loan limit), 提取条件 (withdrawal conditions),
        首付比例 (down payment ratio), 商转公贷 (commercial-to-PF loan conversion), 还款方式 (repayment method).

        For each concept provide:
        - name: concise Chinese term (2-8 characters)
        - description: one-sentence explanation of what this concept means in context

        Return ONLY valid JSON:
        {"concepts": [{"name": "...", "description": "..."}]}
        Return at LEAST 10 concepts. If you can't find 10, create as many as you can.
        """;

    private int extractConcepts() {
        List<DocumentEntity> docs = documentRepository.findAllByOrderByCreatedAtDesc(
                org.springframework.data.domain.PageRequest.of(0, 500)).getContent();

        // Build representative summary: each policy title + first 3 clauses with clause numbers
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

            // Now link concepts to clauses: for each concept, search chunks that mention it
            linkConceptsToClauses(concepts);

            log.info("Extracted {} concepts", concepts.size());
            return concepts.size();

        } catch (Exception e) {
            log.warn("Concept extraction failed: {}", e.getMessage());
            return 0;
        }
    }

    private void linkConceptsToClauses(List<Map<String, Object>> concepts) {
        var allChunks = chunkRepository.findAll();
        for (Map<String, Object> concept : concepts) {
            String name = (String) concept.get("name");
            if (name == null || name.isBlank()) continue;

            int linked = 0;
            for (ChunkEntity chunk : allChunks) {
                if (chunk.getText() == null || !chunk.getText().contains(name)) continue;

                if (chunk.getClauseNumber() != null) {
                    neo4jService.createMentionsRelation(chunk.getClauseNumber(), name, null);
                    linked++;
                } else if (chunk.getParentTitle() != null) {
                    // Chapter intro chunk — link concept to Chapter node
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
                if (linked >= 5) break; // cap mentions per concept
            }
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /**
     * Fuzzy match a possibly-abbreviated or variant title to a known policy title.
     */
    private String fuzzyMatchTitle(String raw, Map<String, String> normalizeMap) {
        if (raw == null) return null;

        // Try exact match first
        if (normalizeMap.containsKey(raw)) return normalizeMap.get(raw);

        // Try normalized match
        String normalized = raw.replaceAll("[（(](?:试行|修订|暂行|\\d{4}).*$", "").trim();
        for (Map.Entry<String, String> e : normalizeMap.entrySet()) {
            if (e.getKey().equals(normalized) || e.getKey().contains(normalized) || normalized.contains(e.getKey())) {
                return e.getValue();
            }
        }

        // Try sub-word match: raw appears as substring of any known title
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
