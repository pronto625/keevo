package com.keevo.admin.platform.domain.model;

import java.time.Instant;

/**
 * HealthSample — one row of {@code public.platform_health_samples}, written every 5 minutes
 * (Story 9-5, FR83). Counters cover the 5 minutes before {@code sampledAt}.
 *
 * @param sampledAt           sample time (primary key)
 * @param dbOk                result of the database check
 * @param requestsTotal       HTTP requests served during the window
 * @param errors5xx           of which answered with a 5xx status
 * @param p50Ms               median latency (null without traffic)
 * @param p95Ms               95th percentile latency (null without traffic)
 * @param p99Ms               99th percentile latency (null without traffic)
 * @param dbActiveConnections active pool connections at sample time (null when unknown)
 */
public record HealthSample(
        Instant sampledAt,
        boolean dbOk,
        long requestsTotal,
        long errors5xx,
        Integer p50Ms,
        Integer p95Ms,
        Integer p99Ms,
        Integer dbActiveConnections
) {}
