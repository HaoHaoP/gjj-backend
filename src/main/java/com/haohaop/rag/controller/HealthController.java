package com.haohaop.rag.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.haohaop.rag.model.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "健康检查", description = "健康检查端点")
public class HealthController {

    @GetMapping("/api/health")
    @Operation(summary = "健康检查", description = "服务运行正常时返回 OK")
    public ResponseEntity<ApiResponse<Map<String, String>>> health() {
        return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "UP")));
    }
}
