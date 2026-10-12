package com.keevo.reporting.export.adapter.out.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Export executor (Story v1s-18-1): background export jobs run here, never on the request
 * thread nor on {@code notificationExecutor}. Two worker threads (core = max = 2, idle threads time
 * out), queue 20, threads {@code export-N} — a {@link ThreadPoolExecutor} only grows past its core
 * size when the queue is full, so core = max is what lets two jobs run concurrently.
 * Saturation rejects the task ({@code AbortPolicy}), which the service turns into
 * {@code EXPORT_QUEUE_FULL} (429).
 *
 * <p>Deliberately a plain {@link ThreadPoolExecutor}, not a Spring {@code TaskExecutor}: a second
 * {@code TaskExecutor} bean would make {@code notificationExecutor} non-unique and silently move
 * every bare {@code @Async} listener to {@code SimpleAsyncTaskExecutor}. Shut down with the context.
 */
@Configuration
@EnableConfigurationProperties(ExportProperties.class)
public class ExportExecutorConfig {

    public static final String EXPORT_EXECUTOR = "exportExecutor";

    @Bean(name = EXPORT_EXECUTOR, destroyMethod = "shutdown")
    public ExecutorService exportExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "export-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(20), factory, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
