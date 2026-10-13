package com.keevo.admin.platform.application.service;

import com.keevo.admin.platform.domain.model.HealthSample;
import com.keevo.admin.platform.domain.model.SystemHealth;
import com.keevo.admin.platform.domain.port.out.HealthSampleRepository;
import com.keevo.admin.platform.domain.port.out.PlatformProbePort;
import com.keevo.admin.platform.domain.port.out.RuntimeMetricsPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformHealthService (Story 9-5)")
class PlatformHealthServiceTest {

    static final Instant NOW = Instant.parse("2026-10-13T10:03:00Z");

    @Mock HealthSampleRepository samples;
    @Mock PlatformProbePort probe;
    @Mock RuntimeMetricsPort metrics;

    MutableClock clock;
    PlatformHealthService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        service = new PlatformHealthService(samples, probe, metrics, clock);
        lenient().when(probe.databaseOk()).thenReturn(true);
        lenient().when(metrics.activeDbConnections()).thenReturn(3);
        lenient().when(metrics.maxDbConnections()).thenReturn(10);
    }

    private static HealthSample sample(Instant at, long requests, long errors, Integer p99) {
        return new HealthSample(at, true, requests, errors, 40, 200, p99, 3);
    }

    private void healthyWindow(int p99, long requests, long errors) {
        Instant last = Instant.parse("2026-10-13T10:00:00Z");
        when(samples.latest()).thenReturn(Optional.of(sample(last, requests, errors, p99)));
        when(samples.recent(NOW.minus(Duration.ofHours(1)), 12)).thenReturn(List.of(sample(last, requests, errors, p99)));
    }

    @Test
    @DisplayName("healthy: 0.5 % errors, p99 800 ms — nothing breached, no incident")
    void healthy() {
        when(samples.firstSampleAt()).thenReturn(Optional.of(NOW.minus(Duration.ofDays(40))));
        when(samples.countOkSince(NOW.minus(Duration.ofDays(30)))).thenReturn(8640L);
        healthyWindow(800, 1000, 5);
        when(probe.syncRefusalsSince(NOW.minus(Duration.ofHours(1)))).thenReturn(12L);

        SystemHealth h = service.health();

        assertThat(h.uptimePercent30d()).isEqualTo(100.0);
        assertThat(h.errorRatePercent1h()).isCloseTo(0.5, within(1e-9));
        assertThat(h.p99Ms()).isEqualTo(800);
        assertThat(h.syncRefusals1h()).isEqualTo(12);
        assertThat(h.dbActiveConnections()).isEqualTo(3);
        assertThat(h.incident()).isFalse();
        assertThat(h.breachedMetrics()).isEmpty();
    }

    @Test
    @DisplayName("error spike: 3 % over the last hour breaches the error rate")
    void errorSpike() {
        when(samples.firstSampleAt()).thenReturn(Optional.empty());
        Instant last = Instant.parse("2026-10-13T10:00:00Z");
        when(samples.latest()).thenReturn(Optional.of(sample(last, 100, 3, 500)));
        when(samples.recent(any(), anyInt())).thenReturn(List.of(
                sample(last, 100, 3, 500), sample(last.minus(Duration.ofMinutes(5)), 100, 3, 500)));
        when(probe.syncRefusalsSince(any())).thenReturn(0L);

        SystemHealth h = service.health();

        assertThat(h.errorRatePercent1h()).isCloseTo(3.0, within(1e-9));
        assertThat(h.errorRateBreached()).isTrue();
        assertThat(h.breachedMetrics()).containsExactly(SystemHealth.Metric.ERROR_RATE);
        assertThat(h.incident()).isTrue();
    }

    @Test
    @DisplayName("thresholds: p99 > 2000 ms, sync refusals > 1000 / h, exactly at the threshold is fine")
    void thresholds() {
        when(samples.firstSampleAt()).thenReturn(Optional.empty());
        healthyWindow(2001, 1000, 20);
        when(probe.syncRefusalsSince(any())).thenReturn(1001L);
        SystemHealth h = service.health();
        assertThat(h.latencyBreached()).isTrue();
        assertThat(h.syncRefusalsBreached()).isTrue();
        assertThat(h.errorRateBreached()).isFalse(); // 2 % is not > 2 %
    }

    @Test
    @DisplayName("server down 2 h: 24 missing samples lower the uptime")
    void uptimeWithGaps() {
        // first sample 10 days ago: one sample expected per 5 min, the current tick minus its grace
        Instant first = NOW.minus(Duration.ofDays(10));
        when(samples.firstSampleAt()).thenReturn(Optional.of(first));
        long expected = (Duration.ofDays(10).toMinutes() - 2) / 5 + 1; // 2-min grace of the current tick
        when(samples.countOkSince(first)).thenReturn(expected - 24);

        Double uptime = service.uptime(NOW);

        assertThat(uptime).isCloseTo((expected - 24) * 100.0 / expected, within(1e-9));
        assertThat(uptime).isLessThan(100.0);
    }

    @Test
    @DisplayName("uptime < 99 % is breached")
    void uptimeBreached() {
        Instant first = NOW.minus(Duration.ofDays(1));
        when(samples.firstSampleAt()).thenReturn(Optional.of(first));
        when(samples.countOkSince(first)).thenReturn(200L); // of 289 expected
        healthyWindow(100, 10, 0);
        when(probe.syncRefusalsSince(any())).thenReturn(0L);
        SystemHealth h = service.health();
        assertThat(h.uptimeBreached()).isTrue();
        assertThat(h.breachedMetrics()).containsExactly(SystemHealth.Metric.UPTIME);
    }

    @Test
    @DisplayName("fresh deploy: the sample of the current tick is not expected before its grace delay")
    void freshDeployGrace() {
        Instant first = Instant.parse("2026-10-13T10:00:00Z"); // NOW = 10:03, next tick 10:05
        when(samples.firstSampleAt()).thenReturn(Optional.of(first));
        when(samples.countOkSince(first)).thenReturn(1L);
        assertThat(service.uptime(NOW)).isEqualTo(100.0);
        clock.set(Instant.parse("2026-10-13T10:06:00Z")); // 10:05 tick due but still in grace
        assertThat(service.uptime(clock.instant())).isEqualTo(100.0);
    }

    @Test
    @DisplayName("no samples yet: uptime null and not breached, latency / error rate empty")
    void noSamples() {
        when(samples.firstSampleAt()).thenReturn(Optional.empty());
        when(samples.latest()).thenReturn(Optional.empty());
        when(samples.recent(any(), anyInt())).thenReturn(List.of());
        when(probe.syncRefusalsSince(any())).thenReturn(0L);

        SystemHealth h = service.health();

        assertThat(h.uptimePercent30d()).isNull();
        assertThat(h.uptimeBreached()).isFalse();
        assertThat(h.p99Ms()).isNull();
        assertThat(h.errorRatePercent1h()).isNull();
        assertThat(h.lastSampleAt()).isNull();
        assertThat(h.incident()).isFalse();
    }

    @Test
    @DisplayName("database check fails: database breached, nothing else read from the database")
    void dbDown() {
        when(probe.databaseOk()).thenReturn(false);

        SystemHealth h = service.health();

        assertThat(h.databaseOk()).isFalse();
        assertThat(h.databaseBreached()).isTrue();
        assertThat(h.breachedMetrics()).containsExactly(SystemHealth.Metric.DATABASE);
        assertThat(h.uptimePercent30d()).isNull();
        assertThat(h.syncRefusals1h()).isNull();
        verifyNoInteractions(samples);
        verify(probe, never()).syncRefusalsSince(any());
    }

    @Test
    @DisplayName("the check passes but a later database read fails: database ❌, metrics empty, no exception")
    void dbReadFailsAfterCheck() {
        when(samples.firstSampleAt()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("gone"));

        SystemHealth h = service.health();

        assertThat(h.databaseOk()).isFalse();
        assertThat(h.breachedMetrics()).containsExactly(SystemHealth.Metric.DATABASE);
        assertThat(h.uptimePercent30d()).isNull();
        assertThat(h.p99Ms()).isNull();
        assertThat(h.errorRatePercent1h()).isNull();
        assertThat(h.syncRefusals1h()).isNull();
        assertThat(service.recentHealth().databaseOk()).isFalse();
    }

    @Test
    @DisplayName("refusal count fails (company loop): database ❌, still answered")
    void refusalsFail() {
        when(samples.firstSampleAt()).thenReturn(Optional.empty());
        when(samples.latest()).thenReturn(Optional.empty());
        when(samples.recent(any(), anyInt())).thenReturn(List.of());
        when(probe.syncRefusalsSince(any())).thenThrow(new org.springframework.dao.QueryTimeoutException("slow"));
        assertThat(service.health().databaseBreached()).isTrue();
    }

    @Test
    @DisplayName("active connections ≥ 80 % of the pool size are breached")
    void connectionsThreshold() {
        when(probe.databaseOk()).thenReturn(false);
        when(metrics.activeDbConnections()).thenReturn(8);
        assertThat(service.health().dbActiveConnectionsBreached()).isTrue();
        when(metrics.activeDbConnections()).thenReturn(7);
        SystemHealth h = service.health();
        assertThat(h.dbActiveConnectionsBreached()).isFalse();
        assertThat(h.dbMaxConnections()).isEqualTo(10);
        when(metrics.maxDbConnections()).thenReturn(null);
        when(metrics.activeDbConnections()).thenReturn(100);
        assertThat(service.health().dbActiveConnectionsBreached()).isFalse();
    }

    @Test
    @DisplayName("recentHealth reuses a computation of the last 30 s")
    void recentHealthCached() {
        when(probe.databaseOk()).thenReturn(false);
        service.recentHealth();
        clock.set(NOW.plusSeconds(20));
        service.recentHealth();
        verify(probe, times(1)).databaseOk();
        clock.set(NOW.plusSeconds(31));
        service.recentHealth();
        verify(probe, times(2)).databaseOk();
    }

    @Test
    void errorRateWithoutRequestsIsZero() {
        assertThat(PlatformHealthService.errorRate(List.of(sample(NOW, 0, 0, null)))).isZero();
        assertThat(PlatformHealthService.errorRate(List.of())).isNull();
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
