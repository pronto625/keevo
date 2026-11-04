package com.keevo.identity.auth.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Story 19.2 — in-memory limits on sign-in code requests (public endpoint: no tenant, so the tenant
 * {@code RateLimitFilter} does not apply). Fixed windows like {@link PasswordResetRateLimiter}: one bounded Caffeine
 * cache per window ({@code expireAfterWrite} = window, {@value #MAX_ENTRIES} entries, ticker from the {@link Clock}).
 *
 * <ul>
 *   <li>per identifier: {@code keevo.auth.otp.identifier-max-per-10-minutes} (3) and
 *       {@code keevo.auth.otp.identifier-max-per-hour} (5)</li>
 *   <li>per IP: {@code keevo.auth.otp.ip-max-per-hour} (10)</li>
 * </ul>
 * Every window counts every request; exceeding any → {@code OTP_TOO_MANY_REQUESTS}. O(1) per request.
 *
 * <p>Code checks are limited per IP too ({@code keevo.auth.otp.verify-per-ip-per-hour}, 30), for every identifier
 * including the store-review account (whose fixed code has no challenge and no failure count).
 *
 * <p>Counters are per instance: a restart resets them, N instances multiply the limits, and {@code maximumSize}
 * eviction can reset a window under a flood — the database daily failure cap is the global bound.
 */
@Component
public class OtpRateLimiter {

    static final long MAX_ENTRIES = 100_000;

    private final Cache<String, AtomicInteger> identifierTenMinutes;
    private final Cache<String, AtomicInteger> identifierHour;
    private final Cache<String, AtomicInteger> ipHour;
    private final Cache<String, AtomicInteger> verifyIpHour;
    private final int identifierMaxTenMinutes;
    private final int identifierMaxHour;
    private final int ipMaxHour;
    private final int verifyIpMaxHour;

    public OtpRateLimiter(Clock clock,
                          @Value("${keevo.auth.otp.identifier-max-per-10-minutes:3}") int identifierMaxTenMinutes,
                          @Value("${keevo.auth.otp.identifier-max-per-hour:5}") int identifierMaxHour,
                          @Value("${keevo.auth.otp.ip-max-per-hour:10}") int ipMaxHour,
                          @Value("${keevo.auth.otp.verify-per-ip-per-hour:30}") int verifyIpMaxHour) {
        this.identifierTenMinutes = window(Duration.ofMinutes(10), clock);
        this.identifierHour = window(Duration.ofHours(1), clock);
        this.ipHour = window(Duration.ofHours(1), clock);
        this.identifierMaxTenMinutes = identifierMaxTenMinutes;
        this.identifierMaxHour = identifierMaxHour;
        this.ipMaxHour = ipMaxHour;
        this.verifyIpHour = window(Duration.ofHours(1), clock);
        this.verifyIpMaxHour = verifyIpMaxHour;
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
     * Counts one code request.
     *
     * @param identifier normalized identifier
     * @param clientIp   caller address, null = no IP window
     * @throws DomainException OTP_TOO_MANY_REQUESTS when a window is exceeded
     */
    public void acquire(String identifier, String clientIp) {
        boolean ok = count(identifierTenMinutes, identifier) <= identifierMaxTenMinutes;
        ok &= count(identifierHour, identifier) <= identifierMaxHour;
        if (clientIp != null && !clientIp.isBlank()) {
            ok &= count(ipHour, clientIp) <= ipMaxHour;
        }
        if (!ok) {
            throw new DomainException(ErrorCode.OTP_TOO_MANY_REQUESTS);
        }
    }

    /**
     * Counts one code check from the caller's IP (null or blank = not limited by IP).
     *
     * @throws DomainException OTP_TOO_MANY_REQUESTS when the hourly window is exceeded
     */
    public void acquireVerify(String clientIp) {
        if (clientIp != null && !clientIp.isBlank() && count(verifyIpHour, clientIp) > verifyIpMaxHour) {
            throw new DomainException(ErrorCode.OTP_TOO_MANY_REQUESTS);
        }
    }

    private static int count(Cache<String, AtomicInteger> window, String key) {
        return window.get(key, k -> new AtomicInteger()).incrementAndGet();
    }
}
