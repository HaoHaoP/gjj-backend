package com.haohaop.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SyncService {

    private static final String PYTHON3 = "/opt/homebrew/bin/python3";
    private static final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, SyncTask> tasks = new ConcurrentHashMap<>();
    private final DocumentService documentService;
    private final MinioService minioService;

    public SyncService(DocumentService documentService, MinioService minioService) {
        this.documentService = documentService;
        this.minioService = minioService;
    }

    public String startSync() {
        String taskId = UUID.randomUUID().toString();
        SyncTask task = new SyncTask();
        task.status = "running";
        task.progress = 0;
        tasks.put(taskId, task);

        String deepseekKey = System.getenv("DEEPSEEK_API_KEY");
        if (deepseekKey == null || deepseekKey.isEmpty()) {
            task.status = "failed";
            task.error = "DEEPSEEK_API_KEY not set";
            return taskId;
        }

        new Thread(() -> {
            try {
                File workDir = new File(System.getProperty("user.home"), "Documents/nanning-gjj-rag");

                // ── Step 1: Crawl (0-30%) ──
                task.stage = "crawl";
                ProcessBuilder pb1 = new ProcessBuilder(PYTHON3, "scripts/crawl_policies.py");
                pb1.directory(workDir);
                if (!runAndTrack(pb1, task, 0, 30)) {
                    task.status = "failed";
                    task.error = "爬取政策页面失败";
                    return;
                }

                // ── Step 2: Extract clauses via DeepSeek LLM (30-80%) ──
                task.stage = "extract";
                ProcessBuilder pb2 = new ProcessBuilder(PYTHON3, "scripts/extract_clauses.py");
                pb2.directory(workDir);
                pb2.environment().put("DEEPSEEK_API_KEY", deepseekKey);
                if (!runAndTrack(pb2, task, 30, 80)) {
                    task.status = "failed";
                    task.error = "条款提取失败";
                    return;
                }

                // ── Step 3: Ingest — group clauses by document, save to PG+Milvus+MinIO (80-100%)
                task.stage = "ingest";
                String dataDir = System.getProperty("user.home") + "/Documents/nanning-gjj-rag/data";
                File clauseDir = new File(dataDir, "clauses");
                File policyDir = new File(dataDir, "policies");
                if (clauseDir.exists()) {
                    java.io.File[] files = clauseDir.listFiles((d, name) -> name.endsWith(".json"));
                    if (files != null) {
                        int total = files.length;
                        for (int i = 0; i < total; i++) {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> clauseData = mapper.readValue(files[i], Map.class);
                                String docTitle = (String) clauseData.get("doc_title");
                                @SuppressWarnings("unchecked")
                                java.util.List<Map<String, Object>> clauses =
                                    (java.util.List<Map<String, Object>>) clauseData.get("clauses");
                                if (clauses == null || clauses.isEmpty()) continue;

                                // Build full text from all clauses
                                StringBuilder sb = new StringBuilder();
                                String clauseNum = null;
                                for (Map<String, Object> c : clauses) {
                                    clauseNum = (String) c.get("clause_number");
                                    String text = (String) c.get("text");
                                    sb.append(text).append("\n");
                                }

                                // Save original HTML to MinIO
                                String docId = files[i].getName().replace(".json", "");
                                File htmlFile = new File(policyDir, docId + ".html");
                                String minioPath = null;
                                String filename = null;
                                long fsize = 0;
                                if (htmlFile.exists()) {
                                    filename = docTitle != null ? docTitle + ".html" : docId + ".html";
                                    minioPath = UUID.randomUUID() + "/" + filename;
                                    java.nio.file.Files.copy(htmlFile.toPath(),
                                            new java.io.FileOutputStream("/tmp/sync_upload.html"));
                                    try (java.io.FileInputStream fis = new java.io.FileInputStream("/tmp/sync_upload.html")) {
                                        minioService.upload(minioPath, fis, htmlFile.length(), "text/html");
                                        fsize = htmlFile.length();
                                    }
                                }

                                // Ingest to PG + Milvus
                                documentService.ingestWithMinio(
                                    docTitle != null ? docTitle : clauseNum,
                                    sb.toString(), "SYNC", minioPath, filename, fsize);

                            } catch (Exception e) {
                                log.warn("Failed to ingest {}: {}", files[i].getName(), e.getMessage());
                            }
                            task.progress = 80 + (int)((i + 1) * 20.0 / total);
                        }
                    }
                }

                task.status = "done";
                task.progress = 100;
            } catch (Exception e) {
                log.error("Sync task {} failed", taskId, e);
                SyncTask t = tasks.get(taskId);
                if (t != null) {
                    t.status = "failed";
                    t.error = e.getMessage();
                }
            }
        }).start();

        return taskId;
    }

    private boolean runAndTrack(ProcessBuilder pb, SyncTask task, int progressStart, int progressEnd)
            throws Exception {
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("{")) {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> json = mapper.readValue(line, Map.class);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> progress = (Map<String, Object>) json.get("progress");
                        if (progress != null) {
                            int current = ((Number) progress.get("current")).intValue();
                            int total = ((Number) progress.get("total")).intValue();
                            task.progress = progressStart + (int)((current * 1.0 / total) * (progressEnd - progressStart));
                        }
                    } catch (Exception ignored) {
                        // not a JSON line — ignore
                    }
                }
            }
        }
        int exitCode = p.waitFor();
        return exitCode == 0;
    }

    public SyncTask getStatus(String taskId) {
        return tasks.getOrDefault(taskId, null);
    }

    public static class SyncTask {
        public String status;
        public int progress;
        public String stage;
        public String error;
    }
}
