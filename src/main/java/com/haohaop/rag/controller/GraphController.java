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
@Tag(name = "知识图谱", description = "知识图谱可视化接口")
public class GraphController {

    private final Neo4jService neo4jService;

    public GraphController(Neo4jService neo4jService) {
        this.neo4jService = neo4jService;
    }

    @GetMapping
    @Operation(summary = "获取图谱数据", description = "获取全部节点和边，用于图谱可视化")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getGraph() {
        try {
            Map<String, Object> graph = neo4jService.getAllNodesAndRelations();
            return ResponseEntity.ok(ApiResponse.ok(graph));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.ok(Map.of("nodes", List.of(), "edges", List.of())));
        }
    }
}
