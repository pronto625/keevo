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
 * database lookup, so a flood of made-up tokens costs nothing. One fixed window per key (bounded Caffeine caches with
 * the {@link Clock} ticker, like {@code McpRateLimiter}); O(1) per request; counters per instance. The sign-in codes
 * keep the 19.2 limits (per phone, per IP, daily failure cap).
 *
 * <p>Reprise 11/11/2026 — Bref's {@code login} limit on the password sign-in and the registration: at most
 * {@code keevo.portal.rate-limit.login-per-ip} (30) attempts per IP and
 * {@code keevo.portal.rate-limit.login-per-identifier} (10) per phone / e-mail in a fixed 15-minute window, every
 * attempt counted (right or wrong, like Bref's guard), checked before any password hash is computed.
 */
@Component
public class PortalRateLimiter {

    static final long MAX_ENTRIES = 100_000;
    /** Bref's {@code login} window ({@code windowMs: 900_000}). */
    static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);

    private final Cache<String, AtomicInteger> perSession;
    private final Cache<String, AtomicInteger> failuresPerIp;
    private final Cache<String, AtomicInteger> loginsPerIp;
    private final Cache<String, AtomicInteger> loginsPerIdentifier;
    private final int sessionMax;
    private final int failuresMax;
    private final int loginIpMax;
    private final int loginIdentifierMax;

    public PortalRateLimiter(Clock clock,
                             @Value("${keevo.portal.rate-limit.session-per-minute:60}") int sessionMax,
                             @Value("${keevo.portal.rate-limit.failures-per-ip-per-minute:30}") int failuresMax,
                             @Value("${keevo.portal.rate-limit.login-per-ip:30}") int loginIpMax,
                             @Value("${keevo.portal.rate-limit.login-per-identifier:10}") int loginIdentifierMax) {
        this.perSession = window(clock, Duration.ofMinutes(1));
        this.failuresPerIp = window(clock, Duration.ofMinutes(1));
        this.loginsPerIp = window(clock, LOGIN_WINDOW);
        this.loginsPerIdentifier = window(clock, LOGIN_WINDOW);
        this.sessionMax = sessionMax;
        this.failuresMax = failuresMax;
        this.loginIpMax = loginIpMax;
        this.loginIdentifierMax = loginIdentifierMax;
    }

    private static Cache<String, AtomicInteger> window(Clock clock, Duration length) {
        return Caffeine.newBuilder()
                .expireAfterWrite(length)
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

    /**
     * Counts one password sign-in / registration attempt of the IP and of the identifier (normalized phone or
     * lower-cased e-mail); {@code false} when either is over its 15-minute limit. A null or blank IP is limited by the
     * identifier only, a null identifier by the IP only.
     */
    public boolean tryLogin(String ip, String identifier) {
        boolean ipOk = ip == null || ip.isBlank()
                || loginsPerIp.get(ip, k -> new AtomicInteger()).incrementAndGet() <= loginIpMax;
        boolean idOk = identifier == null || identifier.isBlank()
                || loginsPerIdentifier.get(identifier, k -> new AtomicInteger()).incrementAndGet()
                <= loginIdentifierMax;
        return ipOk && idOk;
    }
}
