package com.keevo.admin.platform.application.service;

import com.keevo.admin.platform.domain.model.HealthSample;
import com.keevo.admin.platform.domain.model.TimerReading;
import com.keevo.admin.platform.domain.port.in.RecordHealthSampleUseCase;
import com.keevo.admin.platform.domain.port.out.HealthSampleRepository;
import com.keevo.admin.platform.domain.port.out.PlatformProbePort;
import com.keevo.admin.platform.domain.port.out.RuntimeMetricsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HealthSampleService — writes one {@code platform_health_samples} row every 5 minutes
 * (Story 9-5, FR83).
 *
 * <p>The registry's HTTP timers are cumulative; the counters of the window are the difference with
 * the previous reading kept in memory (per timer). The first reading after a boot — or a timer whose
 * count went down (re-registered) — counts from zero. Percentiles are the client-side percentiles of
 * the timers, mixed with their request count of the window as weight ({@link LatencyPercentiles}).
 *
 * <p><b>Single backend instance assumed</b>: the previous readings live in this instance's memory. With
 * several instances, ShedLock would run successive ticks on different instances and the deltas
 * (requests, 5xx, latency weights) would be wrong — see {@code docs/DEPLOYMENT_CHECKLIST.md} § 9.
 *
 * <p>When the database check fails the row is still attempted with {@code db_ok = false}; if the
 * insert itself fails the sample is missing, which the uptime counts as down.
 */
@Service
public class HealthSampleService implements RecordHealthSampleUseCase {

    private static final Logger log = LoggerFactory.getLogger(HealthSampleService.class);

    static final Duration RETENTION = Duration.ofDays(90);

    private final RuntimeMetricsPort metrics;
    private final PlatformProbePort probe;
    private final HealthSampleRepository repository;
    private final Clock clock;

    /** Cumulative count per timer at the previous sample (in-memory, per instance). */
    private Map<String, Long> previousCounts;

    public HealthSampleService(RuntimeMetricsPort metrics, PlatformProbePort probe,
                               HealthSampleRepository repository, Clock clock) {
        this.metrics = metrics;
        this.probe = probe;
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public synchronized HealthSample recordSample() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        boolean dbOk = probe.databaseOk();

        // One entry per timer key (a duplicate key, which should not happen, is summed).
        Map<String, TimerReading> byKey = new LinkedHashMap<>();
        for (TimerReading r : metrics.httpServerTimers()) {
            byKey.merge(r.key(), r, (a, b) -> new TimerReading(a.key(), a.count() + b.count(),
                    a.serverError() || b.serverError(), a.p50Ms(), a.p95Ms(), a.p99Ms(), a.maxMs()));
        }

        Map<String, Long> current = new HashMap<>();
        long requests = 0;
        long errors = 0;
        List<LatencyPercentiles.Weighted> weighted = new ArrayList<>();
        for (TimerReading r : byKey.values()) {
            current.put(r.key(), r.count());
            long delta = delta(r.key(), r.count());
            requests += delta;
            if (r.serverError()) errors += delta;
            weighted.add(new LatencyPercentiles.Weighted(delta, r.p50Ms(), r.p95Ms(), r.p99Ms(), r.maxMs()));
        }
        previousCounts = current;

        HealthSample sample = new HealthSample(now, dbOk, requests, errors,
                toMs(LatencyPercentiles.merge(weighted, 0.5)),
                toMs(LatencyPercentiles.merge(weighted, 0.95)),
                toMs(LatencyPercentiles.merge(weighted, 0.99)),
                metrics.activeDbConnections());

        repository.insert(sample);
        int purged = repository.purgeBefore(now.minus(RETENTION));
        if (purged > 0) log.info("Health samples: {} sample(s) older than 90 days purged", purged);
        return sample;
    }

    private long delta(String key, long count) {
        if (previousCounts == null) return count;
        Long prev = previousCounts.get(key);
        if (prev == null || count < prev) return count;
        return count - prev;
    }

    private static Integer toMs(Double value) {
        return value == null ? null : (int) Math.round(value);
    }
}
