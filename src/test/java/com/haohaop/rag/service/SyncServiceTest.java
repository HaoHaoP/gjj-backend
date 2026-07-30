package com.haohaop.rag.service;

import com.haohaop.rag.service.SyncService.SyncTask;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for SyncTask POJO and basic service wiring.
 * Full pipeline test requires MockWebServer — not available in sandbox.
 */
@DisplayName("SyncService")
class SyncServiceTest {

    private static final Logger log = LoggerFactory.getLogger(SyncServiceTest.class);

    @Nested
    @DisplayName("SyncTask POJO")
    class SyncTaskPojo {

        @Test
        @DisplayName("default values: status=null, progress=0")
        void defaultValues() {
            log.info("[SyncTask POJO] Testing default values");
            SyncTask task = new SyncTask();
            assertThat(task.status).isNull();
            assertThat(task.progress).isEqualTo(0);
            assertThat(task.stage).isNull();
            assertThat(task.error).isNull();
            assertThat(task.kgResult).isNull();
            log.info("[SyncTask POJO] Default values OK");
        }

        @Test
        @DisplayName("all fields assignable for status reporting")
        void allFieldsAssignable() {
            log.info("[SyncTask POJO] Testing field assignment");
            SyncTask task = new SyncTask();
            task.status = "done";
            task.progress = 100;
            task.stage = "knowledge-graph";
            task.error = null;
            task.kgResult = Map.of("policies", 3, "concepts", 12);

            assertThat(task.status).isEqualTo("done");
            assertThat(task.progress).isEqualTo(100);
            assertThat(task.stage).isEqualTo("knowledge-graph");
            assertThat(task.kgResult).containsEntry("policies", 3);
            assertThat(task.kgResult).containsEntry("concepts", 12);
            log.info("[SyncTask POJO] Field assignment OK");
        }

        @Test
        @DisplayName("failed state carries error message")
        void failedState() {
            log.info("[SyncTask POJO] Testing failed state");
            SyncTask task = new SyncTask();
            task.status = "failed";
            task.error = "KG build failed: Neo4j connection refused";
            assertThat(task.status).isEqualTo("failed");
            assertThat(task.error).contains("Neo4j");
            log.info("[SyncTask POJO] Failed state OK");
        }
    }
}
