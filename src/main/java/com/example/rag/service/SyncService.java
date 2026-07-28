package com.example.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SyncService {

    private final Map<String, String> tasks = new ConcurrentHashMap<>();

    public String startSync(int chunkSize, int overlapSize, String chunkMode) {
        String taskId = UUID.randomUUID().toString();
        tasks.put(taskId, "running");

        new Thread(() -> {
            try {
                // Step 1: Crawl
                ProcessBuilder pb = new ProcessBuilder("python3", "scripts/crawl_policies.py");
                pb.directory(new java.io.File("../"));
                Process p = pb.start();
                int crawlExit = p.waitFor();

                if (crawlExit != 0) {
                    tasks.put(taskId, "failed");
                    return;
                }

                // Step 2: Extract clauses via LLM + ingest via API
                ProcessBuilder pb2 = new ProcessBuilder("python3", "scripts/extract_clauses.py",
                        "--chunk-size", String.valueOf(chunkSize),
                        "--overlap-size", String.valueOf(overlapSize),
                        "--chunk-mode", chunkMode);
                pb2.directory(new java.io.File("../"));
                pb2.environment().put("API_BASE_URL", "http://localhost:8080");
                Process p2 = pb2.start();
                int extractExit = p2.waitFor();

                tasks.put(taskId, extractExit == 0 ? "done" : "failed");
            } catch (Exception e) {
                log.error("Sync task {} failed", taskId, e);
                tasks.put(taskId, "failed");
            }
        }).start();

        return taskId;
    }

    public String getStatus(String taskId) {
        return tasks.getOrDefault(taskId, "not_found");
    }
}
