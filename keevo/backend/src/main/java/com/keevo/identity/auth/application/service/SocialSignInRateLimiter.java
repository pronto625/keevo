package com.keevo.identity.auth.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.keevo.identity.auth.domain.model.SocialProvider;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Story 19.3 — per-IP limit on refused Google / Apple tokens ({@code keevo.auth.social.ip-max-per-hour}, 30 per
 * endpoint): only verification failures are counted (users behind a carrier-grade NAT share one IP, and a valid token
 * is cheap to verify with cached keys). Once an IP has that many refused tokens in the window, every call from it is
 * answered {@code RATE_LIMIT_EXCEEDED} (429) before verification. One fixed 1-hour window keyed by provider + IP,
 * bounded Caffeine cache with the {@link Clock} ticker, like {@link OtpRateLimiter}. O(1) per call.
 *
 * <p>Counters are per instance (a restart resets them, N instances multiply the limit).
 */
@Component
public class SocialSignInRateLimiter {

    static final long MAX_ENTRIES = 100_000;

    private final Cache<String, AtomicInteger> failuresHour;
    private final int ipMaxHour;

    public SocialSignInRateLimiter(Clock clock,
                                   @Value("${keevo.auth.social.ip-max-per-hour:30}") int ipMaxHour) {
        this.failuresHour = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(1))
                .maximumSize(MAX_ENTRIES)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
        this.ipMaxHour = ipMaxHour;
    }

    /**
     * Refuses the call when this IP already had the maximum of refused tokens for this provider in the window (null or
     * blank IP = not limited). Counts nothing.
     *
     * @throws DomainException RATE_LIMIT_EXCEEDED
     */
    public void checkAllowed(SocialProvider provider, String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return;
        }
        AtomicInteger failures = failuresHour.getIfPresent(key(provider, clientIp));
        if (failures != null && failures.get() >= ipMaxHour) {
            throw new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }

    /** Counts one refused token from this IP (null or blank IP = not counted). */
    public void recordFailure(SocialProvider provider, String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return;
        }
        failuresHour.get(key(provider, clientIp), k -> new AtomicInteger()).incrementAndGet();
    }

    private static String key(SocialProvider provider, String clientIp) {
        return provider.name() + '|' + clientIp;
    }
}
