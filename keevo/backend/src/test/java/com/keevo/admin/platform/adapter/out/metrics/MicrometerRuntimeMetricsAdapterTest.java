package com.keevo.admin.platform.adapter.out.metrics;

import com.keevo.admin.platform.domain.model.TimerReading;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MicrometerRuntimeMetricsAdapter (Story 9-5)")
class MicrometerRuntimeMetricsAdapterTest {

    @SuppressWarnings("unchecked")
    private static MicrometerRuntimeMetricsAdapter adapter(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> registries = mock(ObjectProvider.class);
        when(registries.getIfAvailable()).thenReturn(registry);
        ObjectProvider<DataSource> dataSources = mock(ObjectProvider.class);
        when(dataSources.getIfAvailable()).thenReturn(null);
        return new MicrometerRuntimeMetricsAdapter(registries, dataSources);
    }

    private static Timer timer(MeterRegistry r, String outcome, String uri) {
        return Timer.builder("http.server.requests")
                .tags("outcome", outcome, "uri", uri, "method", "GET")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(r);
    }

    @Test
    @DisplayName("one reading per timer: count, 5xx flag, p50 / p95 / p99, max")
    void timers() {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        Timer ok = timer(r, "SUCCESS", "/api/v1/sales");
        for (int i = 1; i <= 100; i++) ok.record(Duration.ofMillis(i * 10L));
        Timer ko = timer(r, "SERVER_ERROR", "/api/v1/sync");
        ko.record(Duration.ofMillis(500));
        ko.record(Duration.ofMillis(500));

        List<TimerReading> readings = adapter(r).httpServerTimers().stream()
                .sorted(Comparator.comparing(TimerReading::count)).toList();

        assertThat(readings).hasSize(2);
        TimerReading error = readings.get(0);
        assertThat(error.count()).isEqualTo(2);
        assertThat(error.serverError()).isTrue();
        TimerReading success = readings.get(1);
        assertThat(success.count()).isEqualTo(100);
        assertThat(success.serverError()).isFalse();
        assertThat(success.p50Ms()).isBetween(400.0, 600.0);
        assertThat(success.p95Ms()).isBetween(850.0, 1050.0);
        assertThat(success.p99Ms()).isBetween(900.0, 1100.0);
        assertThat(success.p50Ms()).isLessThanOrEqualTo(success.p95Ms());
        assertThat(success.maxMs()).isEqualTo(1000.0);
        assertThat(success.key()).isNotEqualTo(error.key());
    }

    @Test
    @DisplayName("no registry → no reading, no connection count")
    void noRegistry() {
        MicrometerRuntimeMetricsAdapter adapter = adapter(null);
        assertThat(adapter.httpServerTimers()).isEmpty();
        assertThat(adapter.activeDbConnections()).isNull();
        assertThat(adapter.maxDbConnections()).isNull();
    }

    @Test
    @DisplayName("Hikari gauges summed over pools; null without gauge nor Hikari pool")
    void hikari() {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        MicrometerRuntimeMetricsAdapter adapter = adapter(r);
        assertThat(adapter.activeDbConnections()).isNull();

        Gauge.builder("hikaricp.connections.active", () -> 3).tag("pool", "a").register(r);
        Gauge.builder("hikaricp.connections.active", () -> 2).tag("pool", "b").register(r);
        Gauge.builder("hikaricp.connections.max", () -> 10).tag("pool", "a").register(r);

        assertThat(adapter.activeDbConnections()).isEqualTo(5);
        assertThat(adapter.maxDbConnections()).isEqualTo(10);
    }
}
