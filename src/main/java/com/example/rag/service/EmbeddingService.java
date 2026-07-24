package com.example.rag.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class EmbeddingService {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String embeddingUrl;

    public EmbeddingService(OkHttpClient httpClient, ObjectMapper objectMapper,
                            @Value("${embedding.url}") String embeddingUrl) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.embeddingUrl = embeddingUrl;
    }

    public List<List<Float>> encode(List<String> texts) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("sentences", texts));
            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(embeddingUrl + "/encode")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new RuntimeException("Embedding service returned " + response.code());
                }
                String responseBody = response.body() != null ? response.body().string() : "[]";
                Map<String, Object> result = objectMapper.readValue(responseBody,
                        new TypeReference<Map<String, Object>>() {});
                @SuppressWarnings("unchecked")
                List<List<Double>> doubleEmbeddings = (List<List<Double>>) result.get("encodings");
                return doubleEmbeddings.stream()
                        .map(list -> list.stream()
                                .map(Double::floatValue)
                                .toList())
                        .toList();
            }
        } catch (IOException e) {
            log.error("Failed to encode texts", e);
            throw new RuntimeException("Embedding request failed", e);
        }
    }
}
