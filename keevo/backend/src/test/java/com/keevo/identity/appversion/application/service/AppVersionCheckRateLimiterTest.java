package com.keevo.identity.appversion.application.service;

import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("19.10 — AppVersionCheckRateLimiter")
class AppVersionCheckRateLimiterTest {

    /** A clock the test moves forward. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-05T10:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    @DisplayName("120 calls per minute per IP, then 429 RATE_LIMIT_EXCEEDED; other IPs and the next minute free")
    void limitsPerIpAndMinute() {
        MutableClock clock = new MutableClock();
        AppVersionCheckRateLimiter limiter = new AppVersionCheckRateLimiter(clock, 120);
        for (int i = 0; i < 120; i++) {
            limiter.acquire("10.0.0.1");
        }
        assertThatThrownBy(() -> limiter.acquire("10.0.0.1"))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getErrorCode())
                                .isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED));
        assertThatCode(() -> limiter.acquire("10.0.0.2")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquire(null)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquire(" ")).doesNotThrowAnyException();

        clock.now = clock.now.plus(Duration.ofSeconds(61));
        assertThatCode(() -> limiter.acquire("10.0.0.1")).doesNotThrowAnyException();
    }
}
