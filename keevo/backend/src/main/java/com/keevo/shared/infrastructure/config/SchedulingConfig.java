package com.keevo.shared.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * SchedulingConfig — enables Spring's {@code @Scheduled} jobs (moved from {@code KeevoApplication}).
 *
 * <p>On by default ({@code keevo.scheduling.enabled} missing or {@code true}), so every deployed
 * profile behaves exactly as before. The {@code test} profile turns it off: full-context
 * integration tests share one database, and a real cron tick (hourly day closure, weekly report,
 * sales trends, the 03:00 account deletion) firing mid-test would act on other tests' tenants and
 * make them flaky. Tests call the job methods directly instead. ShedLock
 * ({@link SchedulerLockConfig}) stays active, so a direct call still goes through the lock proxy.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "keevo.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
