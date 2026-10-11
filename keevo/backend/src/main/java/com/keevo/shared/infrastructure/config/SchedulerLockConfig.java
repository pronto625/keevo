package com.keevo.shared.infrastructure.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * SchedulerLockConfig — ShedLock wiring (Story 13.9).
 *
 * <p>Every DB-driven {@code @Scheduled} job carries {@code @SchedulerLock} so that, on a
 * multi-instance deploy, a given tick runs on one instance only. Locks live in
 * {@code public.shedlock} (Flyway V11) and use the database clock ({@code usingDbTime()}) so
 * instance clock skew cannot shorten a lock. In-memory sweeps (notification batch flush/evict)
 * are deliberately NOT locked: each instance owns its own map.</p>
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .withTableName("public.shedlock")
                        .usingDbTime()
                        .build());
    }
}
