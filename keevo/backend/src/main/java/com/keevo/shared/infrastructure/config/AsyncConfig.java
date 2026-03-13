package com.keevo.shared.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * AsyncConfig — enables Spring's async task execution ({@code @EnableAsync}).
 *
 * <p>Used by {@link com.keevo.messaging.notification.application.listener.DraftProductNotificationListener}
 * to dispatch notifications on a dedicated thread pool rather than the request thread.
 *
 * <p>Story 2.4.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("notification-");
        executor.initialize();
        return executor;
    }
}
