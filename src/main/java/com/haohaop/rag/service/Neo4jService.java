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
        log.info("Neo4j connected to {}", uri);
    }

    @PreDestroy
    public void close() {
        if (driver != null) driver.close();
    }

    // ========== Indexes ==========

    private void ensureIndexes() {
        try (Session session = driver.session()) {
            session.run("CREATE INDEX IF NOT EXISTS FOR (p:Policy) ON (p.title)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (p:Policy) ON (p.documentId)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (c:Clause) ON (c.clauseNumber)");
            session.run("CREATE INDEX IF NOT EXISTS FOR (c:Concept) ON (c.name)");
        } catch (Exception e) {
            log.warn("Failed to create Neo4j indexes: {}", e.getMessage());
        }
    }

    // ========== Write Operations ==========

    /** Delete all nodes and relationships. */
    public void clearAll() {
        try (Session session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n");
            log.info("Neo4j graph cleared");
        } catch (Exception e) {
            log.error("Failed to clear Neo4j graph", e);
            throw new RuntimeException("Neo4j clearAll failed", e);
        }

    }

    /** Delete all graph nodes for a specific document. */
    public void deleteByDocumentId(String documentId) {
        String cypher = """
            MATCH (p:Policy {documentId: $documentId})
            OPTIONAL MATCH (p)-[:CONTAINS*1..3]->(n)
            DETACH DELETE n
            DETACH DELETE p
        """;
        executeWrite(cypher, Map.of("documentId", documentId));
        log.info("Neo4j: deleted graph for document {}", documentId);
    }

    /** Create or merge a Policy node. */

    /** Create or merge a Policy node. */
    public void createPolicyNode(String documentId, String title, String source) {
        String cypher = """
            MERGE (p:Policy {title: $title})
            ON CREATE SET p.documentId = $documentId, p.source = $source, p.createdAt = timestamp()
            ON MATCH SET p.documentId = $documentId, p.source = $source
        """;
        executeWrite(cypher, Map.of("documentId", documentId, "title", title, "source", source));
    }

    /** Create a Clause node linked to its parent Policy via CONTAINS. */
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

    /** Create a Chapter node. */
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

    /** Append intro text to a Chapter node (for chapter-intro chunks in MARKDOWN mode). */
    public void appendChapterText(String policyTitle, String chapterNumber, String text) {
        String key = policyTitle + "||" + chapterNumber;
        String cypher = """
            MATCH (ch:Chapter {key: $key})
            SET ch.introText = coalesce(ch.introText, '') + $text
        """;
        executeWrite(cypher, Map.of("key", key, "text", abbreviate(text, 500)));
    }

    /** Create a MENTIONS relationship: Chapter -[:MENTIONS]-> Concept. */
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

    /** Link a Clause to its parent Chapter. */
    public void linkClauseToChapter(String policyTitle, String chapterNumber, String clauseNumber) {
        String key = policyTitle + "||" + chapterNumber;
        String cypher = """
            MATCH (ch:Chapter {key: $key})
            MATCH (c:Clause {clauseNumber: $clauseNumber})
            MERGE (ch)-[:CONTAINS]->(c)
        """;
        executeWrite(cypher, Map.of("key", key, "clauseNumber", clauseNumber));

    }

    /** Create a Section node. */
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

    /** Link a Clause to its parent Section. */
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
     * Create a cross-document reference: Clause -[REL_TYPE]-> Policy.
     * Sanitizes relationType to a valid Cypher label (A-Z, _).
     * Uses CREATE since graph is fully rebuilt after clearAll.
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

    /** Create a Concept node. */
    public void createConceptNode(String name, String description) {
        String cypher = """
            MERGE (c:Concept {name: $name})
            ON CREATE SET c.description = $description
            ON MATCH SET c.description = $description
        """;
        executeWrite(cypher, Map.of("name", name, "description", description));
    }

    /** Create a MENTIONS relationship: Clause -[:MENTIONS]-> Concept. */
    public void createMentionsRelation(String clauseNumber, String conceptName, String evidence) {
        String cypher = """
            MATCH (c:Clause {clauseNumber: $clauseNumber})
            MATCH (co:Concept {name: $conceptName})
            MERGE (c)-[:MENTIONS]->(co)
        """;
        executeWrite(cypher, Map.of("clauseNumber", clauseNumber, "conceptName", conceptName));
    }

    // ========== Read Operations ==========

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
     * Get all nodes and relationships from the graph for visualization.
     * Returns nodes with id, type, label, and edges with source, target, type.
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
            log.warn("Neo4j graph query failed: {}", e.getMessage());
        }

        result.put("nodes", nodes);
        result.put("edges", edges);
        return result;
    }

    // ========== Helpers ==========

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
            log.warn("Neo4j query failed: {}", e.getMessage());
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
            log.error("Neo4j write failed: {} — params: {}", e.getMessage(), params.keySet());
            throw new RuntimeException("Neo4j write failed", e);
        }
    }

    private String abbreviate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
