package com.keevo.sync.sync.application.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * SyncErrorLogCleanupJob — purges sync_error_log entries older than 30 days.
 *
 * <p>Runs daily at 03:00 UTC. Iterates ALL tenant schemas ({@code kv_*})
 * and deletes expired entries in each one. Uses raw JDBC with fully-qualified
 * table names because this job runs outside any TenantContext.
 *
 * <p>Story 5.5 — AC5.
 */
@Component
public class SyncErrorLogCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SyncErrorLogCleanupJob.class);
    private static final long RETENTION_DAYS = 30L;

    private final JdbcTemplate jdbcTemplate;

    public SyncErrorLogCleanupJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void cleanup() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
        List<String> schemas = jdbcTemplate.queryForList(
                "SELECT schema_name FROM information_schema.schemata WHERE schema_name ~ '^kv_[a-z0-9]{6}$'",
                String.class);
        int totalDeleted = 0;
        for (String schema : schemas) {
            try {
                int deleted = jdbcTemplate.update(
                        "DELETE FROM \"" + schema + "\".sync_error_log WHERE created_at < ?",
                        Timestamp.from(cutoff));
                totalDeleted += deleted;
            } catch (Exception e) {
                log.warn("SyncErrorLogCleanupJob: failed to purge schema '{}': {}", schema, e.getMessage());
            }
        }
        if (totalDeleted > 0) {
            log.info("SyncErrorLogCleanupJob: purged {} entries across {} schemas (older than {} days)",
                    totalDeleted, schemas.size(), RETENTION_DAYS);
        }
    }
}
