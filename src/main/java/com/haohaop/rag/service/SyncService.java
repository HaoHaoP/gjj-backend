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
                long deadline = System.currentTimeMillis() + 600_000;
                Map<String, Object> pipelineResult = null;
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
                    if ("done".equals(stStatus)) { pipelineResult = st; break; }
                }

                // ── Step 2: Ingest from MinIO (90-100%) ──
                task.stage = "ingest";
                
                @SuppressWarnings("unchecked")
                List<Map<String, String>> articles = (List<Map<String, String>>) pipelineResult.get("articles");
                if (articles != null) {
                    int total = articles.size();
                    for (int i = 0; i < total; i++) {
                        try {
                            Map<String, String> a = articles.get(i);
                            String docId = a.get("doc_id");
                            String title = a.get("title");
                            String minioPath = a.get("minio_path");
                            String crawlStatus = a.get("crawl_status");
                            
                            // Skip articles already ingested or failed to crawl
                            if ("skipped".equals(crawlStatus) || "failed".equals(crawlStatus)) {
                                continue;
                            }
                            if (minioPath == null || minioPath.isEmpty()) {
                                continue;
                            }
                            
                            // Download MD file from MinIO, use as content for chunking
                            byte[] mdBytes = minioService.download(minioPath).readAllBytes();
                            String mdContent = new String(mdBytes, java.nio.charset.StandardCharsets.UTF_8);

                            // Get MD file size from MinIO
                            long fsize = mdBytes.length;
                            try {
                                fsize = minioService.fileSize(minioPath);
                            } catch (Exception ignored) {}

                            documentService.ingestWithMinio(
                                    title, mdContent, "SYNC", minioPath,
                                    title + ".md", fsize);
                        } catch (Exception e) {
                            log.warn("Ingest failed: {}", e.getMessage());
                        }
                        task.progress = 90 + (int) ((i + 1) * 10.0 / total);
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
