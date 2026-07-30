package com.haohaop.rag.controller;

import com.haohaop.rag.model.ApiResponse;
import com.haohaop.rag.service.Neo4jService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/graph")
@Tag(name = "Graph", description = "Knowledge graph visualization API")
public class GraphController {

    private final Neo4jService neo4jService;

    public GraphController(Neo4jService neo4jService) {
        this.neo4jService = neo4jService;
    }

    @GetMapping
    @Operation(summary = "Get graph data", description = "Get all nodes and edges for graph visualization")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getGraph() {
        try {
            Map<String, Object> graph = neo4jService.getAllNodesAndRelations();
            return ResponseEntity.ok(ApiResponse.ok(graph));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("nodes", List.of(), "edges", List.of())));
        }
    }
}
