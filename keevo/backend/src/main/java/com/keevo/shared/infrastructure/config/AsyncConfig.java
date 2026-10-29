package com.keevo.shared.infrastructure.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * AsyncConfig — enables Spring's async task execution ({@code @EnableAsync}).
 *
 * <p>Story 2.4.
 *
 * <p>Story 12.4:
 * <ul>
 *   <li>{@code notificationExecutor} is the <b>explicit</b> default executor ({@link AsyncConfigurer}): every
 *       bare {@code @Async} method — including the Spring Modulith {@code @ApplicationModuleListener}s —
 *       runs on it. Without this, the Boot {@code taskScheduler} (a second {@code TaskExecutor}, created
 *       when scheduling is on) makes the by-type lookup ambiguous and {@code @Async} silently falls back to
 *       {@code SimpleAsyncTaskExecutor} (one unbounded thread per call, no MDC propagation).</li>
 *   <li>{@link MdcPropagatingTaskDecorator}: the submitter's MDC ({@code traceId}, {@code tenantId},
 *       {@code userId}, …) is copied into the worker thread (routed from review 11).</li>
 *   <li>Queue 50 → 200; on saturation {@link LoggingCallerRunsPolicy} logs a WARN, increments
 *       {@code keevo.async.saturated} and runs the task in the submitting thread (after commit for a module
 *       listener) instead of dropping it until the next resubmission.</li>
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private final ObjectProvider<MeterRegistry> meterRegistry;

    public AsyncConfig(ObjectProvider<MeterRegistry> meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("notification-");
        executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
        executor.setRejectedExecutionHandler(new LoggingCallerRunsPolicy(meterRegistry::getIfAvailable));
        executor.initialize();
        return executor;
    }

    /** Explicit default for {@code @Async} (proxied {@code @Configuration}: returns the singleton bean). */
    @Override
    public Executor getAsyncExecutor() {
        return notificationExecutor();
    }
}
