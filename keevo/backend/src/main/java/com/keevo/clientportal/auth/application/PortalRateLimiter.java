package com.keevo.clientportal.auth.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Story 21.7 — brake on the client portal (public paths: the per-company {@code RateLimitFilter} does not apply):
 * at most {@code keevo.portal.rate-limit.session-per-minute} (60) authenticated requests per session, and
 * {@code keevo.portal.rate-limit.failures-per-ip-per-minute} (30) failed authentications per IP — checked before any
 * database lookup, so a flood of made-up tokens costs nothing. One fixed 1-minute window per key (bounded Caffeine
 * caches with the {@link Clock} ticker, like {@code McpRateLimiter}); O(1) per request; counters per instance. The
 * sign-in codes keep the 19.2 limits (per phone, per IP, daily failure cap).
 */
@Component
public class PortalRateLimiter {

    static final long MAX_ENTRIES = 100_000;

    private final Cache<String, AtomicInteger> perSession;
    private final Cache<String, AtomicInteger> failuresPerIp;
    private final int sessionMax;
    private final int failuresMax;

    public PortalRateLimiter(Clock clock,
                             @Value("${keevo.portal.rate-limit.session-per-minute:60}") int sessionMax,
                             @Value("${keevo.portal.rate-limit.failures-per-ip-per-minute:30}") int failuresMax) {
        this.perSession = window(clock);
        this.failuresPerIp = window(clock);
        this.sessionMax = sessionMax;
        this.failuresMax = failuresMax;
    }

    private static Cache<String, AtomicInteger> window(Clock clock) {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .maximumSize(MAX_ENTRIES)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
    }

    /** Whether this IP already failed authentication too often this minute. Null or blank IP = not limited. */
    public boolean tooManyFailures(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        AtomicInteger failures = failuresPerIp.getIfPresent(ip);
        return failures != null && failures.get() >= failuresMax;
    }

    /** Counts one failed authentication of this IP. */
    public void recordFailure(String ip) {
        if (ip != null && !ip.isBlank()) {
            failuresPerIp.get(ip, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    /** Counts one request of the session (key: its token hash); {@code false} when the session's limit is reached. */
    public boolean tryAcquire(String sessionKey) {
        return perSession.get(sessionKey, k -> new AtomicInteger()).incrementAndGet() <= sessionMax;
    }
}
