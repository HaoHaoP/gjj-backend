package com.haohaop.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@SuppressWarnings("unchecked")
@Service
public class DeepSeekService {

    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String MODEL = "deepseek-v4-flash";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public DeepSeekService(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (this.apiKey == null || this.apiKey.isBlank()) {
            log.warn("未设置 DEEPSEEK_API_KEY 环境变量");
        }
    }

    public String chat(String systemPrompt, String userMessage) {
        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userMessage)
            );

            Map<String, Object> requestBody = Map.of(
                    "model", MODEL,
                    "messages", messages,
                    "stream", false
            );

            String json = objectMapper.writeValueAsString(requestBody);
            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(API_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                return extractContent(response);
            }
        } catch (IOException e) {
            log.error("调用 DeepSeek API 失败", e);
            throw new RuntimeException("DeepSeek API 调用失败", e);
        }
    }

    /**
     * 以 JSON 模式对话。返回解析后的 Map。
     * DeepSeek API 支持 OpenAI 兼容的 response_format：{ "type": "json_object" }。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> chatJson(String systemPrompt, String userMessage) {
        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userMessage)
            );

            Map<String, Object> responseFormat = Map.of("type", "json_object");

            Map<String, Object> requestBody = Map.of(
                    "model", MODEL,
                    "messages", messages,
                    "stream", false,
                    "temperature", 0.1,
                    "response_format", responseFormat
            );

            String json = objectMapper.writeValueAsString(requestBody);
            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(API_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String content = extractContent(response);
                return objectMapper.readValue(content, new TypeReference<Map<String, Object>>() {});
            }
        } catch (IOException e) {
            log.error("调用 DeepSeek JSON API 失败", e);
            throw new RuntimeException("DeepSeek JSON API 调用失败", e);
        }
    }

    @FunctionalInterface
    public interface StreamCallback {
        void onToken(String token, boolean isReasoning);
    }

    /** 流式对话——以 stream=true 调用 DeepSeek API，逐 token 回调。 */
    public void chatStream(String systemPrompt, String userMessage, StreamCallback callback, boolean thinking) {
        try {
            List<Map<String, String>> messages = List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userMessage)
            );
            Map<String, Object> requestBody;
            if (thinking) {
                requestBody = Map.of(
                    "model", MODEL,
                    "messages", messages,
                    "stream", true,
                    "thinking", Map.of("type", "enabled")
                );
            } else {
                // 显式禁用思考：避免模型默认产生推理内容，节省 token 并保证前端不收到 reasoning 事件
                requestBody = Map.of(
                    "model", MODEL,
                    "messages", messages,
                    "stream", true,
                    "thinking", Map.of("type", "disabled")
                );
            }
            String json = objectMapper.writeValueAsString(requestBody);
            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(API_URL)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("DeepSeek 流式 API 返回状态码：" + response.code());
                }
                var respBody = response.body();
                if (respBody == null) return;
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(respBody.byteStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if ("[DONE]".equals(data)) break;
                            try {
                                Map<String, Object> chunk = objectMapper.readValue(data,
                                        new TypeReference<Map<String, Object>>() {});
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> choices =
                                        (List<Map<String, Object>>) chunk.get("choices");
                                if (choices != null && !choices.isEmpty()) {
                                    Map<String, Object> delta =
                                            (Map<String, Object>) choices.get(0).get("delta");
                                    if (delta != null) {
                                        String content = (String) delta.get("content");
                                        String reasoning = (String) delta.get("reasoning_content");
                                        if (content != null) callback.onToken(content, false);
                                        // 仅在开启深度思考时才转发推理内容
                                        if (thinking && reasoning != null) callback.onToken(reasoning, true);
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("DeepSeek 流式调用失败", e);
            throw new RuntimeException("DeepSeek 流式调用失败", e);
        }
    }

    private String extractContent(Response response) throws IOException {
        if (!response.isSuccessful()) {
            String errorBody = response.body() != null ? response.body().string() : "unknown";
            log.error("DeepSeek API 错误：{} - {}", response.code(), errorBody);
            throw new RuntimeException("DeepSeek API 返回 " + response.code() + "：" + errorBody);
        }

        String responseBody = response.body() != null ? response.body().string() : "";
        Map<String, Object> result = objectMapper.readValue(responseBody,
                new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("DeepSeek 响应中没有 choices");
        }

        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return (String) message.get("content");
    }
}
