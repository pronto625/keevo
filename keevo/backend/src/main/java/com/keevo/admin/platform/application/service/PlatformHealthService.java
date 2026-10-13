package com.keevo.admin.platform.application.service;

import com.keevo.admin.platform.domain.model.HealthSample;
import com.keevo.admin.platform.domain.model.SystemHealth;
import com.keevo.admin.platform.domain.port.in.GetSystemHealthUseCase;
import com.keevo.admin.platform.domain.port.out.HealthSampleRepository;
import com.keevo.admin.platform.domain.port.out.PlatformProbePort;
import com.keevo.admin.platform.domain.port.out.RuntimeMetricsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PlatformHealthService — « Santé système » metrics (Story 9-5, FR83).
 *
 * <ul>
 *   <li><b>Uptime 30 d</b> = samples with {@code db_ok} / samples expected (one per 5 min) since
 *       max(30 days ago, first sample) — a missing sample counts as down; null before any sample.</li>
 *   <li><b>Latency</b> p50 / p95 / p99 of the last sample.</li>
 *   <li><b>Error rate 1 h</b> = Σ 5xx / Σ requests of the last 12 samples (last hour).</li>
 *   <li><b>DB connections</b> live pool gauge, breached at ≥ 80 % of the pool size; <b>database</b>
 *       live check.</li>
 *   <li><b>Sync refusals 1 h</b> = {@code sync_error_log} rows of every company created in the last hour.</li>
 * </ul>
 *
 * <p>When the database check fails, or a later database read fails, the page still answers (200)
 * with the database ❌ and the database-derived metrics empty.
 *
 * <p>Not {@code @Transactional}: the per-company sync refusal counts autocommit one by one.
 */
@Service
public class PlatformHealthService implements GetSystemHealthUseCase {

    private static final Logger log = LoggerFactory.getLogger(PlatformHealthService.class);

    /** Fixed: the sampler cron is not configurable ({@code PlatformHealthSampleScheduler}). */
    public static final Duration SAMPLE_PERIOD = Duration.ofMinutes(5);
    static final Duration UPTIME_WINDOW = Duration.ofDays(30);
    /** A tick's sample is expected only once this delay has passed (the job writes it within seconds). */
    static final Duration SAMPLE_GRACE = Duration.ofMinutes(2);
    static final Duration ERROR_WINDOW = Duration.ofHours(1);
    static final int ERROR_WINDOW_SAMPLES = 12;
    /** {@link #recentHealth()} reuses a computation this recent. */
    static final Duration RECENT_TTL = Duration.ofSeconds(30);

    private final HealthSampleRepository samples;
    private final PlatformProbePort probe;
    private final RuntimeMetricsPort metrics;
    private final Clock clock;
    private final ReentrantLock recentLock = new ReentrantLock();
    private volatile SystemHealth cached;

    public PlatformHealthService(HealthSampleRepository samples, PlatformProbePort probe,
                                 RuntimeMetricsPort metrics, Clock clock) {
        this.samples = samples;
        this.probe = probe;
        this.metrics = metrics;
        this.clock = clock;
    }

    private record DbMetrics(Double uptime, HealthSample last, Double errorRate, Long refusals) {}

    @Override
    public SystemHealth health() {
        Instant now = clock.instant();
        boolean dbOk = probe.databaseOk();
        Integer connections = metrics.activeDbConnections();
        Integer maxConnections = metrics.maxDbConnections();

        DbMetrics db = null;
        if (dbOk) {
            try {
                db = new DbMetrics(
                        uptime(now),
                        samples.latest().orElse(null),
                        errorRate(samples.recent(now.minus(ERROR_WINDOW), ERROR_WINDOW_SAMPLES)),
                        probe.syncRefusalsSince(now.minus(ERROR_WINDOW)));
            } catch (DataAccessException e) {
                log.warn("Health: database read failed after a successful check ({})", e.getClass().getSimpleName());
                dbOk = false;
            }
        }

        Double uptime = db == null ? null : db.uptime();
        HealthSample last = db == null ? null : db.last();
        Integer p99 = last == null ? null : last.p99Ms();
        Double errorRate = db == null ? null : db.errorRate();
        Long refusals = db == null ? null : db.refusals();

        SystemHealth health = new SystemHealth(
                uptime, uptime != null && uptime < SystemHealth.UPTIME_THRESHOLD_PERCENT,
                last == null ? null : last.p50Ms(), last == null ? null : last.p95Ms(), p99,
                p99 != null && p99 > SystemHealth.P99_THRESHOLD_MS,
                errorRate, errorRate != null && errorRate > SystemHealth.ERROR_RATE_THRESHOLD_PERCENT,
                connections, maxConnections, SystemHealth.connectionsBreached(connections, maxConnections),
                dbOk,
                refusals, refusals != null && refusals > SystemHealth.SYNC_REFUSALS_THRESHOLD,
                last == null ? null : last.sampledAt(), now);
        cached = health;
        return health;
    }

    /** Single-flight: concurrent callers wait for one computation and reuse it. */
    @Override
    public SystemHealth recentHealth() {
        SystemHealth last = cached;
        if (fresh(last)) return last;
        recentLock.lock();
        try {
            last = cached;
            return fresh(last) ? last : health();
        } finally {
            recentLock.unlock();
        }
    }

    private boolean fresh(SystemHealth h) {
        return h != null && h.checkedAt().plus(RECENT_TTL).isAfter(clock.instant());
    }

    /** Percentage of expected samples that exist with {@code db_ok}; null before the first sample. */
    Double uptime(Instant now) {
        Optional<Instant> first = samples.firstSampleAt();
        if (first.isEmpty()) return null;
        Instant windowStart = now.minus(UPTIME_WINDOW);
        Instant start = first.get().isAfter(windowStart) ? first.get() : windowStart;
        if (start.isAfter(now)) return null;
        Instant due = now.minus(SAMPLE_GRACE);
        long expected = due.isAfter(start) ? Duration.between(start, due).dividedBy(SAMPLE_PERIOD) + 1 : 1;
        long ok = samples.countOkSince(start);
        return Math.min(100.0, ok * 100.0 / expected);
    }

    /** Σ 5xx / Σ requests in percent; null without any sample, 0 when no request was served. */
    static Double errorRate(List<HealthSample> window) {
        if (window.isEmpty()) return null;
        long requests = window.stream().mapToLong(HealthSample::requestsTotal).sum();
        long errors = window.stream().mapToLong(HealthSample::errors5xx).sum();
        if (requests <= 0) return 0.0;
        return errors * 100.0 / requests;
    }
}
