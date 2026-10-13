package com.keevo.admin.platform.domain.model;

/**
 * TimerReading — cumulative state of one HTTP server timer (one tag combination of
 * {@code http.server.requests}) read from the metrics registry (Story 9-5).
 *
 * @param key         stable identity of the timer (its tag set)
 * @param count       cumulative request count since the timer was registered
 * @param serverError true when the timer's outcome is {@code SERVER_ERROR} (5xx)
 * @param p50Ms       client-side 50th percentile (decaying window), null when unavailable
 * @param p95Ms       client-side 95th percentile, null when unavailable
 * @param p99Ms       client-side 99th percentile, null when unavailable
 * @param maxMs       decaying max, null when unavailable
 */
public record TimerReading(
        String key,
        long count,
        boolean serverError,
        Double p50Ms,
        Double p95Ms,
        Double p99Ms,
        Double maxMs
) {}
