package com.haohaop.rag.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
public class SyncService {

    @Value("${pipeline.url:http://localhost:8001}")
    private String pipelineUrl;
    private final DocumentService documentService;
    private final KnowledgeGraphService knowledgeGraphService;
    private final OkHttpClient httpClient;
    
    /** Polling interval in ms between pipeline status checks. Package-private for tests. */
    long pollIntervalMs = 2000;
    private final Map<String, SyncTask> tasks = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Production constructor — creates a default OkHttpClient. */
    @Autowired
    public SyncService(DocumentService documentService,
                        KnowledgeGraphService knowledgeGraphService) {
        this(documentService, knowledgeGraphService,
                new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(600, TimeUnit.SECONDS)
                        .build());
    }

    /** Constructor with injectable OkHttpClient (for testing). */
    SyncService(DocumentService documentService,
                 KnowledgeGraphService knowledgeGraphService, OkHttpClient httpClient) {
        this.documentService = documentService;
        this.knowledgeGraphService = knowledgeGraphService;
        this.httpClient = httpClient;
    }

    public String startSync() {
        String taskId = UUID.randomUUID().toString();
        SyncTask task = new SyncTask();
        task.status = "running";
        task.progress = 0;
        tasks.put(taskId, task);

        new Thread(() -> {
            try {
                // ── Step 1: Call pipeline (extract now ingests via API, 0-85%) ──
                task.stage = "crawl+extract";
                log.info("Calling pipeline: POST {}/pipeline/sync", pipelineUrl);

                RequestBody emptyBody = RequestBody.create("", MediaType.parse("application/json"));
                Request req = new Request.Builder().url(pipelineUrl + "/pipeline/sync").post(emptyBody).build();
                String respBody;
                try (Response resp = httpClient.newCall(req).execute()) {
                    respBody = resp.body() != null ? resp.body().string() : "{}";
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> syncResp = mapper.readValue(respBody, Map.class);
                String pipeTaskId = (String) syncResp.get("taskId");
                log.info("Pipeline task started: {}", pipeTaskId);

                long deadline = System.currentTimeMillis() + 600_000;
                Map<String, Object> pipelineResult = null;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(pollIntervalMs);
                    Request statusReq = new Request.Builder()
                            .url(pipelineUrl + "/pipeline/sync/" + pipeTaskId).get().build();
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
                        task.progress = Math.min(((Number) stProgress).intValue(), 85);
                    }

                    if ("failed".equals(stStatus)) {
                        task.status = "failed";
                        task.error = (String) st.getOrDefault("error", "Pipeline 失败");
                        return;
                    }
                    if ("done".equals(stStatus)) { pipelineResult = st; break; }
                }

                if (pipelineResult == null) {
                    task.status = "failed";
                    task.error = "Pipeline 超时";
                    return;
                }

                // ── Step 2: Build Knowledge Graph (85-100%) ──
                task.stage = "knowledge-graph";
                task.progress = 85;
                log.info("Starting knowledge graph build...");
                try {
                    Map<String, Object> kgResult = knowledgeGraphService.buildAll();
                    log.info("Knowledge graph built: {}", kgResult);
                    task.kgResult = kgResult;
                } catch (Exception e) {
                    log.error("Knowledge graph build failed", e);
                    task.status = "failed";
                    task.error = "KG build failed: " + e.getMessage();
                    return;
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
        public Map<String, Object> kgResult;
    }
}
