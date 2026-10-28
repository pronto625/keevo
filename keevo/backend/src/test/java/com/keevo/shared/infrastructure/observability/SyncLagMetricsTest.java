package com.keevo.shared.infrastructure.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SyncLagMetricsTest — Story 11.2: {@code keevo_sync_lag_seconds} = seconds since the most recent
 * push; 0 with no push; a failed refresh keeps the last value. The SQL itself runs on PostgreSQL in
 * {@code PrometheusScrapeEndpointIntegrationTest}.
 */
@DisplayName("SyncLagMetrics (keevo_sync_lag_seconds)")
class SyncLagMetricsTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    @DisplayName("query uses the MOST RECENT push (MAX), never the oldest device")
    void usesMostRecentPush() {
        assertThat(SyncLagMetrics.LAG_QUERY).contains("MAX(last_push_at)").doesNotContain("MIN(");
    }

    @Test
    @DisplayName("before any refresh, and with no push yet → 0")
    void zeroWhenNoPush() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncLagMetrics metrics = new SyncLagMetrics(registry, jdbc);
        assertThat(registry.get("keevo.sync.lag").gauge().value()).isZero();

        when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(0.0);
        metrics.refresh();

        assertThat(registry.get("keevo.sync.lag").gauge().value()).isZero();
    }

    @Test
    @DisplayName("refresh publishes the lag; a failing query keeps the last value")
    void failureKeepsLastValue() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SyncLagMetrics metrics = new SyncLagMetrics(registry, jdbc);

        when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(42.5);
        metrics.refresh();
        assertThat(registry.get("keevo.sync.lag").gauge().value()).isEqualTo(42.5);

        when(jdbc.queryForObject(anyString(), eq(Double.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        metrics.refresh();
        assertThat(registry.get("keevo.sync.lag").gauge().value()).isEqualTo(42.5);
    }

    @Test
    @DisplayName("null result → 0; negative (clock skew) → 0")
    void nullOrNegativeIsZero() {
        SyncLagMetrics metrics = new SyncLagMetrics(new SimpleMeterRegistry(), jdbc);

        when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(10.0, null);
        metrics.refresh();
        metrics.refresh();
        assertThat(metrics.currentLagSeconds()).isZero();

        when(jdbc.queryForObject(anyString(), eq(Double.class))).thenReturn(-3.0);
        metrics.refresh();
        assertThat(metrics.currentLagSeconds()).isZero();
    }

    @Test
    @DisplayName("Prometheus exposition name: keevo_sync_lag_seconds")
    void prometheusName() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new SyncLagMetrics(registry, jdbc);

        assertThat(registry.scrape()).contains("keevo_sync_lag_seconds 0.0");
    }
}
