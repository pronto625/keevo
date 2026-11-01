package com.keevo.identity.auth.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("16.11 — refresh rate limit (per token, per IP)")
class RefreshRateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-01T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private static void assertLimited(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getErrorCode())
                .isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED);
    }

    @Test
    @DisplayName("per-token budget, then the window resets")
    void perToken() {
        RefreshRateLimiter limiter = new RefreshRateLimiter(clock, 2, 100);
        limiter.acquire("t1", "1.1.1.1");
        limiter.acquire("t1", "1.1.1.2");
        assertLimited(() -> limiter.acquire("t1", "1.1.1.3"));
        now.set(now.get().plusSeconds(61));
        assertThatCode(() -> limiter.acquire("t1", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("per-IP budget trips across distinct tokens, other IPs unaffected, window resets")
    void perIp() {
        RefreshRateLimiter limiter = new RefreshRateLimiter(clock, 10, 3);
        limiter.acquire("a", "9.9.9.9");
        limiter.acquire("b", "9.9.9.9");
        limiter.acquire("c", "9.9.9.9");
        assertLimited(() -> limiter.acquire("d", "9.9.9.9"));
        assertThatCode(() -> limiter.acquire("e", "8.8.8.8")).doesNotThrowAnyException();
        now.set(now.get().plusSeconds(61));
        assertThatCode(() -> limiter.acquire("f", "9.9.9.9")).doesNotThrowAnyException();
    }
}
