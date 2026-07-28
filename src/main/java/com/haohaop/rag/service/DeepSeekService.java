package com.haohaop.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DeepSeekService {

    private static final String API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String MODEL = "deepseek-chat";

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
        } catch (IOException e) {
            log.error("Failed to call DeepSeek API", e);
            throw new RuntimeException("DeepSeek API call failed", e);
        }
    }
}
