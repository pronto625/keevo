package com.keevo.identity.auth.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.keevo.shared.domain.PhoneNumbers;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>Story 12.12 — bounded: one Caffeine cache per window, {@code expireAfterWrite} = window,
 * {@code maximumSize} 100 000, ticker from the injected {@link Clock}. A bucket is created on the
 * first request of a window and counts every request until it expires (fixed window); expired
 * buckets are evicted, so per-phone state no longer grows without bound. The key is the
 * normalised phone ({@link PhoneNumbers#normalize}), so formatting variants share one bucket.
 */
@Component
public class PasswordResetRateLimiter {

    static final int PER_MINUTE_MAX = 1;
    static final int PER_HOUR_MAX = 5;
    static final long MAX_ENTRIES = 100_000;

    private final Cache<String, AtomicInteger> perMinute;
    private final Cache<String, AtomicInteger> perHour;

    public PasswordResetRateLimiter(Clock clock) {
        this.perMinute = window(Duration.ofMinutes(1), clock);
        this.perHour = window(Duration.ofHours(1), clock);
    }

    private static Cache<String, AtomicInteger> window(Duration window, Clock clock) {
        return Caffeine.newBuilder()
                .expireAfterWrite(window)
                .maximumSize(MAX_ENTRIES)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
    }

    /**
     * Try to acquire a rate-limit slot for the given phone number.
     *
     * @param phoneNumber the phone number (normalised internally)
     * @return {@code true} if within both rate limits, {@code false} if either window is exceeded
     */
    public boolean tryAcquire(String phoneNumber) {
        String key = String.valueOf(PhoneNumbers.normalize(phoneNumber));
        // Both windows always count the request (same semantics as the pre-12.12 buckets).
        boolean minuteOk = perMinute.get(key, k -> new AtomicInteger()).incrementAndGet() <= PER_MINUTE_MAX;
        boolean hourOk = perHour.get(key, k -> new AtomicInteger()).incrementAndGet() <= PER_HOUR_MAX;
        return minuteOk && hourOk;
    }

    /** Visible for tests — approximate number of live per-hour buckets. */
    long trackedPhones() {
        perHour.cleanUp();
        return perHour.estimatedSize();
    }
}
