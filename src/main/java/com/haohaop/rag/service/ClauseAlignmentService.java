package com.haohaop.rag.service;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;

@Service
public class ClauseAlignmentService {

    private static final Pattern CLAUSE_PATTERN = Pattern.compile(
        "(第[一二三四五六七八九十百千万\\d]+条|[一二三四五六七八九十]+、)"
    );

    /**
     * Verify clause numbers extracted by LLM match expected sequence.
     * Returns issues list (empty = all good).
     */
    public List<String> verify(List<Map<String, String>> clauses) {
        List<String> issues = new ArrayList<>();

        if (clauses.isEmpty()) {
            issues.add("LLM返回0条条款，可能文档为空或非政策文件");
            return issues;
        }

        // Check first clause has expected pattern
        String first = clauses.get(0).getOrDefault("clause_number", "");
        if (!first.matches("第.*条") && !first.matches("[一二三四五六七八九十]+、")) {
            issues.add("首条编号异常: '" + first + "'");
        }

        // Check for duplicate clause numbers
        Set<String> seen = new HashSet<>();
        for (Map<String, String> c : clauses) {
            String num = c.getOrDefault("clause_number", "");
            if (!seen.add(num)) {
                issues.add("重复条款号: " + num);
            }
        }

        // Check text is non-empty
        for (int i = 0; i < clauses.size(); i++) {
            String text = clauses.get(i).getOrDefault("text", "");
            if (text.trim().length() < 10) {
                issues.add("第" + (i + 1) + "条文本过短 ("
                        + text.trim().length() + "字): " + text.trim());
            }
        }

        return issues;
    }

    /**
     * Extract clause numbers from raw text to cross-check LLM output.
     */
    public Set<String> extractClauseNumbers(String text) {
        Set<String> nums = new LinkedHashSet<>();
        Matcher m = CLAUSE_PATTERN.matcher(text);
        while (m.find()) {
            nums.add(m.group(1));
        }
        return nums;
    }

    /**
     * Count clauses found in text vs LLM output.
     */
    public String diff(Set<String> expected, List<Map<String, String>> actual) {
        Set<String> actualNums = new LinkedHashSet<>();
        for (Map<String, String> c : actual) {
            actualNums.add(c.getOrDefault("clause_number", ""));
        }

        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actualNums);
        Set<String> extra = new LinkedHashSet<>(actualNums);
        extra.removeAll(expected);

        if (missing.isEmpty() && extra.isEmpty()) {
            return "条款对齐: √ 完全匹配";
        }
        return String.format("条款对齐: 缺失%s个, 多余%s个",
                missing.size(), extra.size());
    }
}
