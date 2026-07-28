package com.haohaop.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
public class SyncService {

    private static final String PIPELINE_URL = "http://localhost:8001";
    private final DocumentService documentService;
    private final MinioService minioService;
    private final Map<String, SyncTask> tasks = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(600, TimeUnit.SECONDS)
            .build();

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

        new Thread(() -> {
            try { documentService.deleteBySource("SYNC"); } catch (Exception ignored) {}
            try {
                // ── Step 1: Call pipeline (crawl+extract, 0-90%) ──
                task.stage = "crawl+extract";
                log.info("Calling pipeline: POST {}/pipeline/sync", PIPELINE_URL);
                
                RequestBody emptyBody = RequestBody.create("", MediaType.parse("application/json"));
                Request req = new Request.Builder().url(PIPELINE_URL + "/pipeline/sync").post(emptyBody).build();
                String respBody;
                try (Response resp = httpClient.newCall(req).execute()) {
                    respBody = resp.body() != null ? resp.body().string() : "{}";
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> syncResp = mapper.readValue(respBody, Map.class);
                String pipeTaskId = (String) syncResp.get("taskId");
                log.info("Pipeline task started: {}", pipeTaskId);

                // Poll pipeline status
                long deadline = System.currentTimeMillis() + 600_000; // 10min timeout
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(2000);
                    Request statusReq = new Request.Builder()
                            .url(PIPELINE_URL + "/pipeline/sync/" + pipeTaskId).get().build();
                    String statusBody;
                    try (Response resp = httpClient.newCall(statusReq).execute()) {
                        statusBody = resp.body() != null ? resp.body().string() : "{}";
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> st = mapper.readValue(statusBody, Map.class);
                    String stStatus = (String) st.get("status");
                    String stStage = (String) st.get("stage");
                    Object stProgress = st.get("progress");
                    
                    task.stage = stStage != null ? stStage : task.stage;
                    if (stProgress instanceof Number) {
                        task.progress = Math.min(((Number) stProgress).intValue(), 90);
                    }

                    if ("failed".equals(stStatus)) {
                        task.status = "failed";
                        task.error = (String) st.getOrDefault("error", "Pipeline 失败");
                        return;
                    }
                    if ("done".equals(stStatus)) break;
                }

                // ── Step 2: Ingest (90-100%) ──
                task.stage = "ingest";
                String dataDir = System.getProperty("user.home") + "/Documents/nanning-gjj-rag/data";
                File clauseDir = new File(dataDir, "clauses");
                File policyDir = new File(dataDir, "policies");
                if (clauseDir.exists()) {
                    File[] files = clauseDir.listFiles((d, name) -> name.endsWith(".json"));
                    if (files != null) {
                        int total = files.length;
                        for (int i = 0; i < total; i++) {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> cd = mapper.readValue(files[i], Map.class);
                                String docTitle = (String) cd.get("doc_title");
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> clauses = (List<Map<String, Object>>) cd.get("clauses");
                                if (clauses == null || clauses.isEmpty()) continue;

                                StringBuilder sb = new StringBuilder();
                                for (Map<String, Object> c : clauses) {
                                    sb.append((String) c.get("text")).append("\n");
                                }

                                String docId = files[i].getName().replace(".json", "");
                                File mdFile = new File(policyDir + "/cleaned", docId + ".md");
                                String minioPath = null, filename = null;
                                long fsize = 0;
                                if (mdFile.exists()) {
                                    filename = (docTitle != null ? docTitle : docId) + ".md";
                                    minioPath = UUID.randomUUID() + "/" + filename;
                                    java.nio.file.Files.copy(mdFile.toPath(), new FileOutputStream("/tmp/sync_upload.md"));
                                    try (FileInputStream fis = new FileInputStream("/tmp/sync_upload.md")) {
                                        minioService.upload(minioPath, fis, mdFile.length(), "text/markdown");
                                        fsize = mdFile.length();
                                    }
                                }
                                documentService.ingestWithMinio(
                                        docTitle != null ? docTitle : docId,
                                        sb.toString(), "SYNC", minioPath, filename, fsize);
                            } catch (Exception e) {
                                log.warn("Ingest failed for {}: {}", files[i].getName(), e.getMessage());
                            }
                            task.progress = 90 + (int) ((i + 1) * 10.0 / total);
                        }
                    }
                }
                task.status = "done";
                task.progress = 100;
            } catch (Exception e) {
                log.error("Sync failed", e);
                task.status = "failed";
                task.error = e.getMessage();
            }
        }).start();
        return taskId;
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
