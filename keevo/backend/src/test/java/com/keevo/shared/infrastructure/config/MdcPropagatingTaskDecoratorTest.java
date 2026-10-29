package com.keevo.shared.infrastructure.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 12.4 — MDC (traceId / tenantId / userId) follows the task into the async worker thread. */
class MdcPropagatingTaskDecoratorTest {

    private final MdcPropagatingTaskDecorator decorator = new MdcPropagatingTaskDecorator();

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    @DisplayName("the submitter's MDC is visible in the worker, and the worker's own MDC is restored after")
    void propagatesAndRestores() throws Exception {
        MDC.put("traceId", "t-1");
        MDC.put("tenantId", "kv_a");
        MDC.put("userId", "u-1");
        AtomicReference<Map<String, String>> seen = new AtomicReference<>();
        Runnable task = decorator.decorate(() -> seen.set(MDC.getCopyOfContextMap()));
        MDC.clear();

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Map<String, String>> after = new AtomicReference<>();
            worker.submit(() -> MDC.put("workerOwn", "x")).get(5, TimeUnit.SECONDS);
            worker.submit(task).get(5, TimeUnit.SECONDS);
            worker.submit(() -> after.set(MDC.getCopyOfContextMap())).get(5, TimeUnit.SECONDS);

            assertThat(seen.get()).containsEntry("traceId", "t-1").containsEntry("tenantId", "kv_a")
                    .containsEntry("userId", "u-1").doesNotContainKey("workerOwn");
            assertThat(after.get()).isEqualTo(Map.of("workerOwn", "x"));
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    @DisplayName("caller-runs: running in the submitting thread keeps that thread's MDC afterwards")
    void callerRunsKeepsCallerMdc() {
        MDC.put("traceId", "t-submit");
        Runnable task = decorator.decorate(() -> MDC.put("traceId", "changed-inside"));
        MDC.put("traceId", "t-now");

        task.run();

        assertThat(MDC.getCopyOfContextMap()).isEqualTo(new HashMap<>(Map.of("traceId", "t-now")));
    }

    @Test
    @DisplayName("empty submitter MDC → the worker runs with an empty MDC")
    void emptySubmitterMdc() {
        Runnable task = decorator.decorate(() -> assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty());
        MDC.put("stale", "x");
        task.run();
        assertThat(MDC.get("stale")).isEqualTo("x");
    }
}
