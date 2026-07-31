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
    private static final String MODEL = "deepseek-v4-pro";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public DeepSeekService(OkHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (this.apiKey == null || this.apiKey.isBlank()) {
            log.warn("DEEPSEEK_API_KEY environment variable is not set");
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
            log.error("Failed to call DeepSeek API", e);
            throw new RuntimeException("DeepSeek API call failed", e);
        }
    }

    /**
     * Chat with JSON mode enabled. Returns parsed response as a Map.
     * DeepSeek API supports OpenAI-compatible response_format: { "type": "json_object" }.
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
            log.error("Failed to call DeepSeek JSON API", e);
            throw new RuntimeException("DeepSeek JSON API call failed", e);
        }
    }

    @FunctionalInterface
    public interface StreamCallback {
        void onToken(String token, boolean isReasoning);
    }

    /** Streaming chat — calls DeepSeek API with stream=true, invokes callback per token. */
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
                requestBody = Map.of(
                    "model", MODEL,
                    "messages", messages,
                    "stream", true
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
                    throw new RuntimeException("DeepSeek stream API returned " + response.code());
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
                                        if (reasoning != null) callback.onToken(reasoning, true);
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("DeepSeek stream failed", e);
            throw new RuntimeException("DeepSeek stream call failed", e);
        }
    }

    private String extractContent(Response response) throws IOException {
        if (!response.isSuccessful()) {
            String errorBody = response.body() != null ? response.body().string() : "unknown";
            log.error("DeepSeek API error: {} - {}", response.code(), errorBody);
            throw new RuntimeException("DeepSeek API returned " + response.code() + ": " + errorBody);
        }

        String responseBody = response.body() != null ? response.body().string() : "";
        Map<String, Object> result = objectMapper.readValue(responseBody,
                new TypeReference<Map<String, Object>>() {});

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("No choices in DeepSeek response");
        }

        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        return (String) message.get("content");
    }
}
