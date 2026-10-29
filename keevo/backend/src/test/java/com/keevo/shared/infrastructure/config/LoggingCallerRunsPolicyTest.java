package com.keevo.shared.infrastructure.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 12.4 — saturation: counter + the task runs in the caller thread (never dropped while running). */
class LoggingCallerRunsPolicyTest {

    @Test
    @DisplayName("rejected task → keevo.async.saturated incremented, task run in the submitting thread")
    void runsOnCallerAndCounts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
        try {
            AtomicReference<String> ranOn = new AtomicReference<>();
            new LoggingCallerRunsPolicy(() -> registry)
                    .rejectedExecution(() -> ranOn.set(Thread.currentThread().getName()), executor);

            assertThat(ranOn.get()).isEqualTo(Thread.currentThread().getName());
            assertThat(registry.get("keevo.async.saturated").counter().count()).isEqualTo(1.0);
        } finally {
            executor.shutdownNow();
        }
    }
}
