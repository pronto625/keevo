package com.keevo.admin.platform.domain.model;

import java.time.Instant;

/** Test builder of {@link SystemHealth} (Story 9-5): healthy by default, one wither per field. */
public final class SystemHealthFixtures {

    private Double uptime = 100.0;
    private boolean uptimeBreached;
    private Integer p50 = 40;
    private Integer p95 = 300;
    private Integer p99 = 800;
    private boolean latencyBreached;
    private Double errorRate = 0.5;
    private boolean errorRateBreached;
    private Integer connections = 3;
    private Integer maxConnections = 10;
    private boolean connectionsBreached;
    private boolean dbOk = true;
    private Long refusals = 0L;
    private boolean refusalsBreached;
    private Instant lastSampleAt;
    private final Instant checkedAt;

    private SystemHealthFixtures(Instant now) {
        this.checkedAt = now;
        this.lastSampleAt = now.minusSeconds(120);
    }

    public static SystemHealthFixtures healthy(Instant now) {
        return new SystemHealthFixtures(now);
    }

    public SystemHealthFixtures uptime(Double v, boolean breached) { uptime = v; uptimeBreached = breached; return this; }
    public SystemHealthFixtures p99(Integer v, boolean breached) { p99 = v; latencyBreached = breached; return this; }
    public SystemHealthFixtures errorRate(Double v, boolean breached) { errorRate = v; errorRateBreached = breached; return this; }
    public SystemHealthFixtures connections(Integer active, Integer max, boolean breached) {
        connections = active; maxConnections = max; connectionsBreached = breached; return this;
    }
    public SystemHealthFixtures refusals(Long v, boolean breached) { refusals = v; refusalsBreached = breached; return this; }
    public SystemHealthFixtures databaseDown() {
        dbOk = false; uptime = null; p50 = null; p95 = null; p99 = null; errorRate = null; refusals = null;
        lastSampleAt = null; return this;
    }

    public SystemHealth build() {
        return new SystemHealth(uptime, uptimeBreached, p50, p95, p99, latencyBreached, errorRate, errorRateBreached,
                connections, maxConnections, connectionsBreached, dbOk, refusals, refusalsBreached, lastSampleAt, checkedAt);
    }
}
