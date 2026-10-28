package com.keevo.shared.infrastructure.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * SyncLagMetrics — {@code keevo_sync_lag_seconds} gauge (Story 11.2, MOD-NFR4).
 *
 * <p>Seconds since the <b>most recent</b> {@code public.user_sync_state.last_push_at}, across all
 * devices: a platform-wide sync outage detector (it grows when no device manages to push). The oldest
 * push is deliberately not used — abandoned devices would make it grow forever.
 *
 * <p>The gauge reads a cached value refreshed every 60 s (a scrape never hits the database). No push
 * yet → 0. A failed refresh keeps the last value and logs a WARN.
 */
@Component
public class SyncLagMetrics {

    private static final Logger log = LoggerFactory.getLogger(SyncLagMetrics.class);

    static final String SYNC_LAG = "keevo.sync.lag";

    static final String LAG_QUERY =
            "SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - MAX(last_push_at))), 0) "
                    + "FROM public.user_sync_state "
                    + "WHERE last_push_at IS NOT NULL";

    private final JdbcTemplate jdbcTemplate;
    private final AtomicReference<Double> lagSeconds = new AtomicReference<>(0.0);

    public SyncLagMetrics(MeterRegistry meterRegistry, JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        Gauge.builder(SYNC_LAG, lagSeconds, AtomicReference::get)
                .description("Seconds since the most recent device push (platform-wide sync lag)")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void refresh() {
        try {
            Double lag = jdbcTemplate.queryForObject(LAG_QUERY, Double.class);
            // clock skew (push stamped in the future) must not report a negative lag
            lagSeconds.set(lag == null ? 0.0 : Math.max(0.0, lag));
        } catch (RuntimeException e) {
            log.warn("METRICS: sync lag gauge not refreshed, keeping last value: {}", e.getMessage());
        }
    }

    /** Current cached value (tests). */
    double currentLagSeconds() {
        return lagSeconds.get();
    }
}
