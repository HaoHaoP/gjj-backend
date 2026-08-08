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
    
    /** 两次流水线状态轮询的间隔毫秒数。包内可见，便于测试。 */
    long pollIntervalMs = 2000;
    private final Map<String, SyncTask> tasks = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 生产环境构造函数——创建默认 OkHttpClient。 */
    @Autowired
    public SyncService(DocumentService documentService,
                        KnowledgeGraphService knowledgeGraphService) {
        this(documentService, knowledgeGraphService,
                new OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(600, TimeUnit.SECONDS)
                        .build());
    }

    /** 可注入 OkHttpClient 的构造函数（用于测试）。 */
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
                // ── 步骤 1：调用流水线（抽取阶段现在通过 API 入库，0-85%）──
                task.stage = "crawl+extract";
                log.info("调用流水线：POST {}/pipeline/sync", pipelineUrl);

                RequestBody emptyBody = RequestBody.create("", MediaType.parse("application/json"));
                Request req = new Request.Builder().url(pipelineUrl + "/pipeline/sync").post(emptyBody).build();
                String respBody;
                try (Response resp = httpClient.newCall(req).execute()) {
                    respBody = resp.body() != null ? resp.body().string() : "{}";
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> syncResp = mapper.readValue(respBody, Map.class);
                String pipeTaskId = (String) syncResp.get("taskId");
                log.info("流水线任务已启动：{}", pipeTaskId);

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

                // ── 步骤 2：构建知识图谱（非阻塞，失败不会阻断同步）──
                task.stage = "knowledge-graph";
                task.progress = 85;
                log.info("开始构建知识图谱（异步）……");
                try {
                    knowledgeGraphService.buildAll(
                            pct -> task.progress = 85 + pct * 14 / 100);
                    log.info("知识图谱构建完成");
                } catch (Exception e) {
                    log.warn("知识图谱构建失败（同步流程继续）：{}", e.getMessage());
                }

                task.status = "done";
                task.progress = 100;
            } catch (Exception e) {
                log.error("同步失败", e);
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
