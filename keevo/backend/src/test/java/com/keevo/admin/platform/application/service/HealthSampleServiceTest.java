package com.keevo.admin.platform.application.service;

import com.keevo.admin.platform.domain.model.HealthSample;
import com.keevo.admin.platform.domain.model.TimerReading;
import com.keevo.admin.platform.domain.port.out.HealthSampleRepository;
import com.keevo.admin.platform.domain.port.out.PlatformProbePort;
import com.keevo.admin.platform.domain.port.out.RuntimeMetricsPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthSampleService (Story 9-5)")
class HealthSampleServiceTest {

    static final Instant NOW = Instant.parse("2026-10-13T10:05:00.123Z");

    @Mock RuntimeMetricsPort metrics;
    @Mock PlatformProbePort probe;
    @Mock HealthSampleRepository repository;

    HealthSampleService service;

    @BeforeEach
    void setUp() {
        service = new HealthSampleService(metrics, probe, repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TimerReading ok(String key, long count, double p) {
        return new TimerReading(key, count, false, p, p, p, p);
    }

    private static TimerReading error(String key, long count) {
        return new TimerReading(key, count, true, 50.0, 60.0, 70.0, 80.0);
    }

    @Test
    @DisplayName("first sample counts since boot, the next one only the difference (per timer)")
    void deltas() {
        when(probe.databaseOk()).thenReturn(true);
        when(metrics.activeDbConnections()).thenReturn(4);
        when(metrics.httpServerTimers())
                .thenReturn(List.of(ok("a", 100, 20), error("b", 5)))
                .thenReturn(List.of(ok("a", 150, 20), error("b", 8), ok("c", 2, 30)));

        HealthSample first = service.recordSample();
        HealthSample second = service.recordSample();

        assertThat(first.requestsTotal()).isEqualTo(105);
        assertThat(first.errors5xx()).isEqualTo(5);
        assertThat(second.requestsTotal()).isEqualTo(50 + 3 + 2);
        assertThat(second.errors5xx()).isEqualTo(3);
        assertThat(second.sampledAt()).isEqualTo(Instant.parse("2026-10-13T10:05:00Z"));
        assertThat(second.dbActiveConnections()).isEqualTo(4);
        assertThat(second.dbOk()).isTrue();
        verify(repository, org.mockito.Mockito.times(2)).insert(any());
        verify(repository, org.mockito.Mockito.times(2)).purgeBefore(NOW.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).minus(Duration.ofDays(90)));
    }

    @Test
    @DisplayName("a timer whose count went down (re-registered) counts from zero")
    void counterReset() {
        when(probe.databaseOk()).thenReturn(true);
        when(metrics.httpServerTimers())
                .thenReturn(List.of(ok("a", 100, 20)))
                .thenReturn(List.of(ok("a", 7, 20)));
        service.recordSample();
        assertThat(service.recordSample().requestsTotal()).isEqualTo(7);
    }

    @Test
    @DisplayName("no traffic in the window: no percentile")
    void noTraffic() {
        when(probe.databaseOk()).thenReturn(true);
        when(metrics.httpServerTimers()).thenReturn(List.of(ok("a", 10, 20))).thenReturn(List.of(ok("a", 10, 20)));
        service.recordSample();
        HealthSample idle = service.recordSample();
        assertThat(idle.requestsTotal()).isZero();
        assertThat(idle.p50Ms()).isNull();
        assertThat(idle.p99Ms()).isNull();
    }

    @Test
    @DisplayName("percentiles of a single timer are its own")
    void singleTimerPercentiles() {
        when(probe.databaseOk()).thenReturn(true);
        when(metrics.httpServerTimers()).thenReturn(List.of(new TimerReading("a", 10, false, 12.0, 80.0, 300.0, 900.0)));
        HealthSample s = service.recordSample();
        assertThat(s.p50Ms()).isEqualTo(12);
        assertThat(s.p95Ms()).isEqualTo(80);
        assertThat(s.p99Ms()).isEqualTo(300);
    }

    @Test
    @DisplayName("database check failed: the sample is still written with db_ok = false")
    void dbDown() {
        when(probe.databaseOk()).thenReturn(false);
        when(metrics.httpServerTimers()).thenReturn(List.of());
        service.recordSample();
        ArgumentCaptor<HealthSample> captor = ArgumentCaptor.forClass(HealthSample.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().dbOk()).isFalse();
    }

    @Test
    @DisplayName("insert failure propagates (the sample is missing, counted as down) and nothing is purged")
    void insertFails() {
        when(probe.databaseOk()).thenReturn(false);
        when(metrics.httpServerTimers()).thenReturn(List.of());
        doThrow(new DataAccessResourceFailureException("down")).when(repository).insert(any());
        assertThatThrownBy(() -> service.recordSample()).isInstanceOf(DataAccessResourceFailureException.class);
        verify(repository, never()).purgeBefore(any());
    }
}
