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
 * Story 19.4 — per-IP brake on password logins ({@code keevo.auth.login.ip-max-failures-per-hour}, 50): only failed
 * logins ({@code INVALID_CREDENTIALS}, phone or username path) are counted, a successful login never is (members of a
 * shop share one IP). Once an IP has that many failures in the window, every login from it is answered
 * {@code RATE_LIMIT_EXCEEDED} (429) before the account lookup — it stops password spraying of short Bref passwords
 * across many usernames, which the per-account lockout cannot. One fixed 1-hour window per IP, bounded Caffeine cache
 * with the {@link Clock} ticker, like {@link SocialSignInRateLimiter}. O(1) per call.
 *
 * <p>Counters are per instance (a restart resets them, N instances multiply the limit).
 */
@Component
public class LoginRateLimiter {

    static final long MAX_ENTRIES = 100_000;

    private final Cache<String, AtomicInteger> failuresHour;
    private final int ipMaxHour;

    public LoginRateLimiter(Clock clock,
                            @Value("${keevo.auth.login.ip-max-failures-per-hour:50}") int ipMaxHour) {
        this.failuresHour = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(1))
                .maximumSize(MAX_ENTRIES)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
        this.ipMaxHour = ipMaxHour;
    }

    /**
     * Refuses the login when this IP already had the maximum of failed logins in the window (null or blank IP = not
     * limited). Counts nothing.
     *
     * @throws DomainException RATE_LIMIT_EXCEEDED
     */
    public void checkAllowed(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return;
        }
        AtomicInteger failures = failuresHour.getIfPresent(clientIp);
        if (failures != null && failures.get() >= ipMaxHour) {
            throw new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }

    /** Counts one failed login from this IP (null or blank IP = not counted). */
    public void recordFailure(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return;
        }
        failuresHour.get(clientIp, k -> new AtomicInteger()).incrementAndGet();
    }
}
