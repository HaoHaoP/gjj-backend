package com.haohaop.rag.config;

import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

@Configuration
public class OkHttpConfig {

    @Bean
    @Primary
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Bean(name = "embeddingHttpClient")
    public OkHttpClient embeddingHttpClient(
            @Value("${embedding.read-timeout-seconds:1800}") long readTimeoutSeconds,
            @Value("${embedding.call-timeout-seconds:1800}") long callTimeoutSeconds) {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                .build();
    }
}
