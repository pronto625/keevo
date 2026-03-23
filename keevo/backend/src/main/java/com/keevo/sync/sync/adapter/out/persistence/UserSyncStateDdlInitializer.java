package com.keevo.sync.sync.adapter.out.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * UserSyncStateDdlInitializer — creates the {@code public.user_sync_state} table
 * at application startup if it does not already exist.
 *
 * <p>This table lives in the PUBLIC schema (not per-tenant) because it tracks
 * per-device last-push timestamps for the offline gate check (Story 5.4).
 *
 * <p>Idempotent — {@code CREATE TABLE IF NOT EXISTS} and {@code CREATE INDEX IF NOT EXISTS}
 * make it safe to run on every startup.
 */
@Component
public class UserSyncStateDdlInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(UserSyncStateDdlInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public UserSyncStateDdlInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("UserSyncStateDdlInitializer — ensuring public.user_sync_state table exists");
        try {
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS public.user_sync_state (
                        device_id    VARCHAR(36)  NOT NULL,
                        user_id      UUID         NOT NULL,
                        tenant_id    VARCHAR(100),
                        last_push_at TIMESTAMPTZ,
                        last_pull_at TIMESTAMPTZ,
                        updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                        PRIMARY KEY (device_id)
                    )
                    """);
            jdbcTemplate.execute("""
                    CREATE INDEX IF NOT EXISTS idx_user_sync_state_user
                        ON public.user_sync_state (user_id)
                    """);
            log.info("UserSyncStateDdlInitializer — public.user_sync_state ready");
        } catch (Exception e) {
            log.error("UserSyncStateDdlInitializer — DDL failed: {}", e.getMessage(), e);
        }
    }
}
