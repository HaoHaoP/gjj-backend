package com.example.rag.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    @GetMapping("/results")
    public ResponseEntity<Map<String, Object>> results() {
        var categories = List.of(
            Map.of("category", "条件查询", "total", 7, "ours", 7, "doubao", 7),
            Map.of("category", "材料准备", "total", 7, "ours", 7, "doubao", 7),
            Map.of("category", "流程指引", "total", 7, "ours", 7, "doubao", 7),
            Map.of("category", "金额计算", "total", 7, "ours", 7, "doubao", 7),
            Map.of("category", "陷阱题", "total", 3, "ours", 3, "doubao", 0, "highlight", true)
        );

        var traps = List.of(
            Map.of("question", "南宁住房公积金是否支持装修提取？",
                   "ourSystem", "✅ 正确拒答：未找到相关政策条款",
                   "doubao", "❌ 胡编了不存在的装修提取流程"),
            Map.of("question", "办理住房公积金贷款需要提供单位营业执照复印件吗？",
                   "ourSystem", "✅ 正确拒答：未找到相关规定",
                   "doubao", "❌ 列出了不存在的材料清单"),
            Map.of("question", "如何办理住房公积金销户提取？（陷阱版）",
                   "ourSystem", "✅ 正确拒答",
                   "doubao", "❌ 详细回答了不适用场景的流程")
        );

        return ResponseEntity.ok(Map.of(
            "categories", categories,
            "traps", traps
        ));
    }
}
