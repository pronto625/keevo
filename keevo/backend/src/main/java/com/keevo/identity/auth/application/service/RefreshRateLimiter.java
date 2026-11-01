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
 * Story 16.11 — rate limit of {@code POST /api/v1/auth/refresh}, per refresh token and per client
 * IP (fixed one-minute windows, bounded Caffeine buckets, same model as
 * {@link PasswordResetRateLimiter}). A real app renews once per access-token lifetime (30 min);
 * the per-IP budget is generous because mobile carriers share addresses (CGNAT).
 *
 * <p>Keys are the token <b>hash</b> (the raw token is never kept or logged) and the client IP.
 * Exceeded → {@code RATE_LIMIT_EXCEEDED} (429, « Trop de requêtes, veuillez patienter »).
 */
@Component
public class RefreshRateLimiter {

    static final long MAX_ENTRIES = 100_000;

    private final int perTokenPerMinute;
    private final int perIpPerMinute;
    private final Cache<String, AtomicInteger> perToken;
    private final Cache<String, AtomicInteger> perIp;

    public RefreshRateLimiter(Clock clock,
                              @Value("${keevo.auth.refresh-rate-limit.per-token-per-minute:10}") int perTokenPerMinute,
                              @Value("${keevo.auth.refresh-rate-limit.per-ip-per-minute:300}") int perIpPerMinute) {
        this.perTokenPerMinute = perTokenPerMinute;
        this.perIpPerMinute = perIpPerMinute;
        this.perToken = window(clock);
        this.perIp = window(clock);
    }

    private static Cache<String, AtomicInteger> window(Clock clock) {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .maximumSize(MAX_ENTRIES)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
    }

    /**
     * Counts one refresh call.
     *
     * @param rawRefreshToken presented token (only its hash is used as key)
     * @param clientIp        client IP, may be null
     * @throws DomainException RATE_LIMIT_EXCEEDED when either window is exceeded
     */
    public void acquire(String rawRefreshToken, String clientIp) {
        boolean tokenOk = true;
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            String key = SessionTokenIssuer.hash(rawRefreshToken);
            tokenOk = perToken.get(key, k -> new AtomicInteger()).incrementAndGet() <= perTokenPerMinute;
        }
        boolean ipOk = true;
        if (clientIp != null && !clientIp.isBlank()) {
            ipOk = perIp.get(clientIp, k -> new AtomicInteger()).incrementAndGet() <= perIpPerMinute;
        }
        if (!tokenOk || !ipOk) {
            throw new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many session renewals");
        }
    }
}
