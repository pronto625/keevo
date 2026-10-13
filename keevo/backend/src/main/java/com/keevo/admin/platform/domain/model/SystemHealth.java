package com.keevo.admin.platform.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SystemHealth — live view of the platform health (Story 9-5, FR83). Each metric carries its
 * {@code breached} flag; a null value means « not measurable yet » and never breaches.
 *
 * <p>Thresholds: error rate &gt; 2 %, p99 &gt; 2000 ms, sync refusals &gt; 1000 / h, database ❌,
 * uptime &lt; 99 %, active database connections ≥ 80 % of the pool size.
 */
public record SystemHealth(
        Double uptimePercent30d,
        boolean uptimeBreached,
        Integer p50Ms,
        Integer p95Ms,
        Integer p99Ms,
        boolean latencyBreached,
        Double errorRatePercent1h,
        boolean errorRateBreached,
        Integer dbActiveConnections,
        Integer dbMaxConnections,
        boolean dbActiveConnectionsBreached,
        boolean databaseOk,
        Long syncRefusals1h,
        boolean syncRefusalsBreached,
        Instant lastSampleAt,
        Instant checkedAt
) {

    public static final double ERROR_RATE_THRESHOLD_PERCENT = 2.0;
    public static final int P99_THRESHOLD_MS = 2000;
    public static final long SYNC_REFUSALS_THRESHOLD = 1000;
    public static final double UPTIME_THRESHOLD_PERCENT = 99.0;
    public static final double DB_CONNECTIONS_THRESHOLD_RATIO = 0.8;

    /** Active connections at or above 80 % of the pool size (false when either is unknown). */
    public static boolean connectionsBreached(Integer active, Integer max) {
        return active != null && max != null && max > 0 && active >= DB_CONNECTIONS_THRESHOLD_RATIO * max;
    }

    /** French phrase of each breached metric (incident banner, joined with « et »). */
    public enum Metric {
        ERROR_RATE("Taux d'erreur au-dessus de 2 %"),
        LATENCY("Latence p99 au-dessus de 2 s"),
        SYNC_REFUSALS("Refus de synchronisation au-dessus de 1 000/h"),
        DATABASE("Base de données injoignable"),
        DB_CONNECTIONS("Connexions à la base au-dessus de 80 %"),
        UPTIME("Disponibilité sous 99 %");

        private final String label;

        Metric(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public boolean databaseBreached() {
        return !databaseOk;
    }

    /** Metrics over their threshold right now, most urgent first. */
    public List<Metric> breachedMetrics() {
        List<Metric> out = new ArrayList<>();
        if (databaseBreached()) out.add(Metric.DATABASE);
        if (errorRateBreached) out.add(Metric.ERROR_RATE);
        if (latencyBreached) out.add(Metric.LATENCY);
        if (syncRefusalsBreached) out.add(Metric.SYNC_REFUSALS);
        if (dbActiveConnectionsBreached) out.add(Metric.DB_CONNECTIONS);
        if (uptimeBreached) out.add(Metric.UPTIME);
        return out;
    }

    public boolean incident() {
        return !breachedMetrics().isEmpty();
    }
}
