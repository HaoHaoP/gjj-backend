package com.haohaop.rag.config;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OkHttpConfigTest {

    @Test
    void embeddingClientUsesLongConfiguredTimeouts() {
        OkHttpClient client = new OkHttpConfig().embeddingHttpClient(1800, 1800);

        assertThat(client.readTimeoutMillis()).isEqualTo(1800_000);
        assertThat(client.callTimeoutMillis()).isEqualTo(1800_000);
    }
}
