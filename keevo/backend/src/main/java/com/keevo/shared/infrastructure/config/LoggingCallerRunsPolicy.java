package com.keevo.shared.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

/**
 * LoggingCallerRunsPolicy — saturation of {@code notificationExecutor} (Story 12.4): WARN log + counter
 * {@code keevo.async.saturated}, then the task runs in the submitting thread (like
 * {@link ThreadPoolExecutor.CallerRunsPolicy}; discarded only if the executor is shut down).
 */
public class LoggingCallerRunsPolicy implements RejectedExecutionHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingCallerRunsPolicy.class);
    static final String COUNTER = "keevo.async.saturated";

    private final Supplier<MeterRegistry> registry;

    public LoggingCallerRunsPolicy(Supplier<MeterRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
        log.warn("[ASYNC] notificationExecutor saturated (active={}, queued={}) — task runs in the caller thread {}",
                executor.getActiveCount(), executor.getQueue().size(), Thread.currentThread().getName());
        MeterRegistry meters = registry.get();
        if (meters != null) {
            meters.counter(COUNTER).increment();
        }
        if (!executor.isShutdown()) {
            task.run();
        }
    }
}
