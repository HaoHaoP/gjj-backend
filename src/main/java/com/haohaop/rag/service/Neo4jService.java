package com.haohaop.rag.service;

import com.haohaop.rag.model.KgRelation;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class Neo4jService {

    private final String uri;
    private final String username;
    private final String password;
    private Driver driver;

    public Neo4jService(
            @Value("${neo4j.uri}") String uri,
            @Value("${neo4j.username}") String username,
            @Value("${neo4j.password}") String password) {
        this.uri = uri;
        this.username = username;
        this.password = password;
    }

    @PostConstruct
    public void init() {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(username, password));
        ensureIndexes();
        log.info("已连接 Neo4j：{}", uri);
    }

    @PreDestroy
    public void close() {
        if (driver != null) driver.close();
    }

    // ========== 索引 ==========

    private void ensureIndexes() {
        try (Session session = driver.session()) {
            session.run("CREATE INDEX IF NOT EXISTS FOR (p:Policy) ON (p.title)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (p:Policy) ON (p.documentId)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (c:Clause) ON (c.clauseNumber)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (c:Concept) ON (c.name)");
        } catch (Exception e) {
            log.warn("创建 Neo4j 索引失败：{}", e.getMessage());
        }
    }

    // ========== 写操作 ==========

    /** 删除所有节点和关系。 */
    public void clearAll() {
        try (Session session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n");
            log.info("Neo4j 图谱已清空");
        } catch (Exception e) {
            log.error("清空 Neo4j 图谱失败", e);
            throw new RuntimeException("Neo4j clearAll 失败", e);
        }

    }

    /** 删除指定文档的全部图谱节点。 */
    public void deleteByDocumentId(String documentId) {
        String cypher = """
            MATCH (p:Policy {documentId: $documentId})
            OPTIONAL MATCH (p)-[:CONTAINS*1..3]->(n)
            DETACH DELETE n
            DETACH DELETE p
        """;
        executeWrite(cypher, Map.of("documentId", documentId));
        log.info("Neo4j：已删除文档 {} 的图谱", documentId);
    }

    /** 创建或合并政策（Policy）节点。 */

    /** 创建或合并政策（Policy）节点。 */
    public void createPolicyNode(String documentId, String title, String source) {
        String cypher = """
            MERGE (p:Policy {title: $title})
            ON CREATE SET p.documentId = $documentId, p.source = $source, p.createdAt = timestamp()
            ON MATCH SET p.documentId = $documentId, p.source = $source
        """;
        executeWrite(cypher, Map.of("documentId", documentId, "title", title, "source", source));
    }

    /** 创建条款（Clause）节点，并通过 CONTAINS 关联到其所属政策。 */
    public void createClauseNode(String policyTitle, String clauseNumber, String textSnippet, String clauseId) {
        String cypher = """
            MATCH (p:Policy {title: $policyTitle})
            MERGE (c:Clause {clauseNumber: $clauseNumber})
            ON CREATE SET c.text = $text, c.clauseId = $clauseId
            ON MATCH SET c.text = $text, c.clauseId = $clauseId
            MERGE (p)-[:CONTAINS]->(c)
        """;
        executeWrite(cypher, Map.of("policyTitle", policyTitle, "clauseNumber", clauseNumber,
                "text", abbreviate(textSnippet, 300), "clauseId", clauseId));
    }

    /** 创建章（Chapter）节点。 */
    public void createChapterNode(String policyTitle, String chapterNumber, String chapterTitle) {
        String key = policyTitle + "||" + chapterNumber;
        String cypher = """
            MATCH (p:Policy {title: $policyTitle})
            MERGE (ch:Chapter {key: $key})
            SET ch.number = $chapterNumber, ch.title = $chapterTitle
            MERGE (p)-[:CONTAINS]->(ch)
        """;
        executeWrite(cypher, Map.of("policyTitle", policyTitle, "key", key,
                "chapterNumber", chapterNumber, "chapterTitle",
                chapterTitle != null ? chapterTitle : chapterNumber));
    }

    /** 向章节点追加引言文本（用于 MARKDOWN 模式下的章引言分块）。 */
    public void appendChapterText(String policyTitle, String chapterNumber, String text) {
        String key = policyTitle + "||" + chapterNumber;
        String cypher = """
            MATCH (ch:Chapter {key: $key})
            SET ch.introText = coalesce(ch.introText, '') + $text
        """;
        executeWrite(cypher, Map.of("key", key, "text", abbreviate(text, 500)));
    }

    /** 创建 MENTIONS 关系：Chapter -[:MENTIONS]-> Concept。 */
    public void createChapterMentionsRelation(String policyTitle, String chapterNumber, String conceptName) {
        String key = policyTitle + "||" + chapterNumber;
        String cypher = """
            MATCH (ch:Chapter {key: $key})
            MATCH (co:Concept {name: $conceptName})
            MERGE (ch)-[:MENTIONS]->(co)
        """;
        executeWrite(cypher, Map.of("key", key, "conceptName", conceptName));
        executeWrite(cypher, Map.of("key", key, "conceptName", conceptName));
    }

    /** 将条款关联到其所属章。 */
    public void linkClauseToChapter(String policyTitle, String chapterNumber, String clauseNumber) {
        String key = policyTitle + "||" + chapterNumber;
        String cypher = """
            MATCH (ch:Chapter {key: $key})
            MATCH (c:Clause {clauseNumber: $clauseNumber})
            MERGE (ch)-[:CONTAINS]->(c)
        """;
        executeWrite(cypher, Map.of("key", key, "clauseNumber", clauseNumber));

    }

    /** 创建节（Section）节点。 */
    public void createSectionNode(String policyTitle, String chapterNumber, String sectionNumber) {
        String chapterKey = policyTitle + "||" + chapterNumber;
        String sectionKey = chapterKey + "||" + sectionNumber;
        String cypher = """
            MATCH (ch:Chapter {key: $chapterKey})
            MERGE (s:Section {key: $sectionKey})
            SET s.number = $sectionNumber
            MERGE (ch)-[:CONTAINS]->(s)
        """;
        executeWrite(cypher, Map.of("chapterKey", chapterKey, "sectionKey", sectionKey,
                "sectionNumber", sectionNumber));
    }

    /** 将条款关联到其所属节。 */
    public void linkClauseToSection(String policyTitle, String chapterNumber,
                                     String sectionNumber, String clauseNumber) {
        String chapterKey = policyTitle + "||" + chapterNumber;
        String sectionKey = chapterKey + "||" + sectionNumber;
        String cypher = """
            MATCH (s:Section {key: $sectionKey})
            MATCH (c:Clause {clauseNumber: $clauseNumber})
            MERGE (s)-[:CONTAINS]->(c)
        """;
        executeWrite(cypher, Map.of("sectionKey", sectionKey, "clauseNumber", clauseNumber));
    }

    /**
     * 创建跨文档引用关系：Clause -[REL_TYPE]-> Policy。
     * 将 relationType 清洗为合法的 Cypher 标签（A-Z、_）。
     * 由于 clearAll 后图谱会完全重建，这里使用 CREATE。
     */
    public void createCrossReference(String fromClauseNumber, String relationType,
                                      String toPolicyTitle, String evidence) {
        String safeType = sanitizeRelType(relationType);
        if (safeType.isEmpty()) safeType = "REFERENCES";

        String cypher = String.format("""
            MATCH (c:Clause {clauseNumber: $fromClause})
            MATCH (p:Policy {title: $toTitle})
            CREATE (c)-[:%s {evidence: $evidence}]->(p)
        """, safeType);

        executeWrite(cypher, Map.of("fromClause", fromClauseNumber,
                "toTitle", toPolicyTitle, "evidence", abbreviate(evidence, 200)));
    }

    /** 创建概念（Concept）节点。 */
    public void createConceptNode(String name, String description) {
        String cypher = """
            MERGE (c:Concept {name: $name})
            ON CREATE SET c.description = $description
            ON MATCH SET c.description = $description
        """;
        executeWrite(cypher, Map.of("name", name, "description", description));
    }

    /** 创建 MENTIONS 关系：Clause -[:MENTIONS]-> Concept。 */
    public void createMentionsRelation(String clauseNumber, String conceptName, String evidence) {
        String cypher = """
            MATCH (c:Clause {clauseNumber: $clauseNumber})
            MATCH (co:Concept {name: $conceptName})
            MERGE (c)-[:MENTIONS]->(co)
        """;
        executeWrite(cypher, Map.of("clauseNumber", clauseNumber, "conceptName", conceptName));
    }

    // ========== 读操作 ==========

    public List<KgRelation> findRelations(String policyTitle) {
        String query = """
            MATCH (p:Policy)-[:CONTAINS]->(c:Clause)-[r]->(tp:Policy)
            WHERE p.title CONTAINS $title AND type(r) IN ['REFERENCES', 'REVISES', 'ABOLISHES']
            RETURN c.clauseNumber AS fromClause, type(r) AS relation, tp.title AS toDocument
            LIMIT 5
        """;
        List<Map<String, String>> rows = executeQuery(query, Map.of("title", policyTitle));
        return rows.stream()
                .map(row -> new KgRelation(
                        row.get("fromClause"),
                        row.get("relation"),
                        row.get("toDocument")))
                .toList();
    }

    public List<String> listPolicyTitles() {
        String query = "MATCH (p:Policy) RETURN p.title AS title";
        return executeQuery(query, Map.of()).stream()
                .map(row -> row.get("title"))
                .toList();
    }


    /**
     * 获取图谱中的全部节点和关系，用于可视化。
     * 返回带 id、type、label 的节点，以及带 source、target、type 的边。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAllNodesAndRelations() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();

        try (Session session = driver.session()) {
            var nodeResult = session.run("MATCH (n) RETURN id(n) AS id, labels(n) AS labels, properties(n) AS props");
            while (nodeResult.hasNext()) {
                var record = nodeResult.next();
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("id", String.valueOf(record.get("id").asLong()));
                var labels = record.get("labels").asList().stream().map(Object::toString).toList();
                node.put("type", labels.isEmpty() ? "unknown" : labels.get(0).toLowerCase());
                var props = record.get("props").asMap();
                node.put("label", props.getOrDefault("title",
                        props.getOrDefault("clauseNumber",
                        props.getOrDefault("number",
                        props.getOrDefault("name", "unknown")))));
                node.put("properties", props);
                nodes.add(node);
            }

            var edgeResult = session.run("MATCH (a)-[r]->(b) RETURN id(a) AS source, id(b) AS target, type(r) AS rtype, properties(r) AS props");
            while (edgeResult.hasNext()) {
                var record = edgeResult.next();
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("source", String.valueOf(record.get("source").asLong()));
                edge.put("target", String.valueOf(record.get("target").asLong()));
                edge.put("type", record.get("rtype").asString());
                edge.put("properties", record.get("props").asMap());
                edges.add(edge);
            }
        } catch (Exception e) {
            log.warn("Neo4j 图谱查询失败：{}", e.getMessage());
        }

        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    // ========== 辅助方法 ==========

    private String sanitizeRelType(String raw) {
        return raw.replaceAll("[^A-Z_]", "");
    }

    private List<Map<String, String>> executeQuery(String cypher, Map<String, Object> params) {
        List<Map<String, String>> results = new ArrayList<>();
        try (Session session = driver.session()) {
            var result = session.run(cypher, params);
            while (result.hasNext()) {
                var record = result.next();
                Map<String, String> row = new LinkedHashMap<>();
                record.keys().forEach(k -> row.put(k, record.get(k).asString(null)));
                results.add(row);
            }
        } catch (Exception e) {
            log.warn("Neo4j 查询失败：{}", e.getMessage());
        }
        return results;
    }

    private void executeWrite(String cypher, Map<String, Object> params) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                tx.run(cypher, params);
                return null;
            });
        } catch (Exception e) {
            log.error("Neo4j 写入失败：{} — 参数：{}", e.getMessage(), params.keySet());
            throw new RuntimeException("Neo4j 写入失败", e);
        }
    }

    private String abbreviate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
