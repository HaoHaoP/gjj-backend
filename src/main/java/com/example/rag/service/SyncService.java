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

    public String startSync() {
        String taskId = UUID.randomUUID().toString();
        tasks.put(taskId, "running");

        new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder("python3", "scripts/crawl_policies.py");
                pb.directory(new java.io.File("../"));
                Process p = pb.start();
                int exitCode = p.waitFor();

                if (exitCode == 0) {
                    // Run extract_clauses.py after crawl
                    ProcessBuilder pb2 = new ProcessBuilder("python3", "scripts/extract_clauses.py");
                    pb2.directory(new java.io.File("../"));
                    Process p2 = pb2.start();
                    p2.waitFor();
                }

                tasks.put(taskId, exitCode == 0 ? "done" : "failed");
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
