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
        log.info("Neo4j connected to {}", uri);
    }

    @PreDestroy
    public void close() {
        if (driver != null) driver.close();
    }

    /**
     * 查询指定政策标题相关的引用关系。
     * @param policyTitle 政策标题（部分匹配）
     * @return KG 引用关系列表
     */
    public List<KgRelation> findRelations(String policyTitle) {
        String query = """
            MATCH (p:Policy)-[:CONTAINS]->(c:Clause)-[r:REFERENCES|REVISES|ABOLISHES]->(tp:Policy)
            WHERE p.title CONTAINS $title
            RETURN c.number AS fromClause, type(r) AS relation, tp.title AS toDocument
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
}
