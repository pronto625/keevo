package com.keevo.identity.appversion.application.service;

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
 * Story 19.10 — per-IP brake on the public version check ({@code keevo.app-version.check.ip-max-per-minute}, 1200 — many
 * phones share one carrier NAT IP):
 * beyond it the IP is answered {@code RATE_LIMIT_EXCEEDED} (429). One fixed 1-minute window per IP, bounded Caffeine
 * cache with the {@link Clock} ticker, like {@code LoginRateLimiter}. O(1) per call; counters are per instance.
 */
@Component
public class AppVersionCheckRateLimiter {

    static final long MAX_ENTRIES = 100_000;

    private final Cache<String, AtomicInteger> callsMinute;
    private final int ipMaxMinute;

    public AppVersionCheckRateLimiter(Clock clock,
                                      @Value("${keevo.app-version.check.ip-max-per-minute:1200}") int ipMaxMinute) {
        this.callsMinute = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .maximumSize(MAX_ENTRIES)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
        this.ipMaxMinute = ipMaxMinute;
    }

    /**
     * Counts one check from this IP and refuses it beyond the limit (null or blank IP = not limited).
     *
     * @throws DomainException RATE_LIMIT_EXCEEDED
     */
    public void acquire(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return;
        }
        if (callsMinute.get(clientIp, k -> new AtomicInteger()).incrementAndGet() > ipMaxMinute) {
            throw new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }
}
