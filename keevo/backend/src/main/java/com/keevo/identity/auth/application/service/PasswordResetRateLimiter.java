package com.keevo.identity.auth.application.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * PasswordResetRateLimiter — In-memory rate limiter for forgot-password requests.
 *
 * <p>Story 14.12 — Dedicated rate limiter keyed by phoneNumber (NOT tenantId).
 * The existing {@code RateLimitFilter} cannot be reused because:
 * <ul>
 *   <li>It is scoped by {@code TenantContext.getCurrentTenant()} (tenant-level)</li>
 *   <li>It passes through when {@code tenantId == null} (public endpoints)</li>
 *   <li>forgot-password/reset-password are PUBLIC endpoints — no JWT, no TenantContext</li>
 * </ul>
 *
 * <p>Two concurrent windows (D3):
 * <ul>
 *   <li>1 request per minute per phoneNumber</li>
 *   <li>5 requests per hour per phoneNumber</li>
 * </ul>
 *
 * <p>Design: Fixed-window in memory (OK for MVP single-instance).
 * Mirror of {@code RateLimitFilter.RateLimitBucket} pattern with synchronized reset.
 */
@Component
public class PasswordResetRateLimiter {

    private final ConcurrentHashMap<String, WindowBucket> perMinute =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WindowBucket> perHour =
            new ConcurrentHashMap<>();

    /**
     * Try to acquire a rate-limit slot for the given phone number.
     *
     * @param phoneNumber the phone number (E.164 format)
     * @return {@code true} if within both rate limits, {@code false} if either window is exceeded
     */
    public boolean tryAcquire(String phoneNumber) {
        WindowBucket minuteBucket = perMinute.computeIfAbsent(phoneNumber,
                k -> new WindowBucket(1, 60_000L));
        WindowBucket hourBucket = perHour.computeIfAbsent(phoneNumber,
                k -> new WindowBucket(5, 3_600_000L));

        // Check both windows — if either is exceeded, block
        boolean minuteOk = !minuteBucket.isLimitExceeded();
        boolean hourOk   = !hourBucket.isLimitExceeded();

        return minuteOk && hourOk;
    }

    /**
     * Fixed-window rate-limit bucket.
     * Mirror of {@code RateLimitFilter.RateLimitBucket} — synchronized to prevent
     * race conditions on window reset (two threads simultaneously detecting expiry
     * and both resetting the counter).
     */
    static final class WindowBucket {
        private final int maxRequests;
        private final long windowMs;
        private int count;
        private volatile long windowStart;

        WindowBucket(int maxRequests, long windowMs) {
            this.maxRequests = maxRequests;
            this.windowMs = windowMs;
            this.count = 0;
            this.windowStart = System.currentTimeMillis();
        }

        synchronized boolean isLimitExceeded() {
            long now = System.currentTimeMillis();
            if (now - windowStart > windowMs) {
                count = 0;
                windowStart = now;
            }
            return ++count > maxRequests;
        }
    }
}
