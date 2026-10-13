package com.keevo.admin.platform.adapter.out.metrics;

import com.keevo.admin.platform.domain.model.TimerReading;
import com.keevo.admin.platform.domain.port.out.RuntimeMetricsPort;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * MicrometerRuntimeMetricsAdapter — reads the HTTP server timers and the Hikari pool gauge from the
 * Micrometer registry (Story 9-5). Percentiles 0.5 / 0.95 / 0.99 of {@code http.server.requests} are
 * enabled in {@code application.yml}. Without a registry (slice tests) nothing is read; without the
 * Hikari gauge the pool's own MXBean is asked.
 */
@Component
public class MicrometerRuntimeMetricsAdapter implements RuntimeMetricsPort {

    static final String HTTP_TIMER = "http.server.requests";
    static final String HIKARI_ACTIVE = "hikaricp.connections.active";
    static final String HIKARI_MAX = "hikaricp.connections.max";

    private final ObjectProvider<MeterRegistry> registry;
    private final ObjectProvider<DataSource> dataSource;

    public MicrometerRuntimeMetricsAdapter(ObjectProvider<MeterRegistry> registry,
                                           ObjectProvider<DataSource> dataSource) {
        this.registry = registry;
        this.dataSource = dataSource;
    }

    @Override
    public List<TimerReading> httpServerTimers() {
        MeterRegistry r = registry.getIfAvailable();
        if (r == null) return List.of();
        Collection<Timer> timers = r.find(HTTP_TIMER).timers();
        List<TimerReading> out = new ArrayList<>(timers.size());
        for (Timer t : timers) {
            HistogramSnapshot snapshot = t.takeSnapshot();
            Double p50 = null;
            Double p95 = null;
            Double p99 = null;
            for (ValueAtPercentile v : snapshot.percentileValues()) {
                double ms = v.value(TimeUnit.MILLISECONDS);
                if (Math.abs(v.percentile() - 0.5) < 1e-6) p50 = ms;
                else if (Math.abs(v.percentile() - 0.95) < 1e-6) p95 = ms;
                else if (Math.abs(v.percentile() - 0.99) < 1e-6) p99 = ms;
            }
            out.add(new TimerReading(
                    t.getId().getTags().toString(),
                    t.count(),
                    "SERVER_ERROR".equals(t.getId().getTag("outcome")),
                    p50, p95, p99,
                    t.max(TimeUnit.MILLISECONDS)));
        }
        return out;
    }

    @Override
    public Integer activeDbConnections() {
        Integer fromGauge = gaugeSum(HIKARI_ACTIVE);
        if (fromGauge != null) return fromGauge;
        HikariDataSource hikari = hikari();
        if (hikari != null && hikari.getHikariPoolMXBean() != null) {
            return hikari.getHikariPoolMXBean().getActiveConnections();
        }
        return null;
    }

    @Override
    public Integer maxDbConnections() {
        Integer fromGauge = gaugeSum(HIKARI_MAX);
        if (fromGauge != null) return fromGauge;
        HikariDataSource hikari = hikari();
        if (hikari != null && hikari.getHikariConfigMXBean() != null) {
            return hikari.getHikariConfigMXBean().getMaximumPoolSize();
        }
        return null;
    }

    private Integer gaugeSum(String name) {
        MeterRegistry r = registry.getIfAvailable();
        if (r == null) return null;
        Collection<Gauge> gauges = r.find(name).gauges();
        if (gauges.isEmpty()) return null;
        double sum = gauges.stream().mapToDouble(Gauge::value).filter(v -> !Double.isNaN(v)).sum();
        return (int) Math.round(sum);
    }

    private HikariDataSource hikari() {
        try {
            DataSource ds = dataSource.getIfAvailable();
            if (ds != null && ds.isWrapperFor(HikariDataSource.class)) return ds.unwrap(HikariDataSource.class);
        } catch (java.sql.SQLException | RuntimeException ignored) {
            // unknown pool: no value
        }
        return null;
    }
}
