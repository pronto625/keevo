package com.keevo.identity.auth.application.service;

import com.keevo.shared.MutableTestClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PasswordResetRateLimiterTest — Story 14.12 two-window limiter (1/min + 5/hour per phone),
 * Story 12.12 Caffeine-bounded buckets driven by an injected Clock.
 */
@DisplayName("PasswordResetRateLimiter")
class PasswordResetRateLimiterTest {

    MutableTestClock clock = new MutableTestClock(Instant.parse("2026-10-11T10:00:00Z"));
    PasswordResetRateLimiter rateLimiter = new PasswordResetRateLimiter(clock);

    String phone = "+237600000000";

    @Test
    @DisplayName("should allow first request")
    void shouldAllowFirstRequest() {
        assertThat(rateLimiter.tryAcquire(phone)).isTrue();
    }

    @Test
    @DisplayName("should block second request within 1 minute")
    void shouldBlockSecondRequestWithinOneMinute() {
        assertThat(rateLimiter.tryAcquire(phone)).isTrue();
        assertThat(rateLimiter.tryAcquire(phone)).isFalse();
    }

    @Test
    @DisplayName("minute window resets after 60 s (clock-driven)")
    void shouldResetMinuteWindowAfterExpiry() {
        assertThat(rateLimiter.tryAcquire(phone)).isTrue();
        clock.advance(Duration.ofSeconds(30));
        assertThat(rateLimiter.tryAcquire(phone)).isFalse();
        clock.advance(Duration.ofSeconds(31)); // 61 s after the bucket was created
        assertThat(rateLimiter.tryAcquire(phone)).isTrue();
    }

    @Test
    @DisplayName("6th request within the hour is blocked; hour window resets after 60 min")
    void shouldBlockSixthRequestWithinOneHour() {
        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.tryAcquire(phone)).as("request %d", i + 1).isTrue();
            clock.advance(Duration.ofSeconds(61));
        }
        assertThat(rateLimiter.tryAcquire(phone)).isFalse(); // 6th, ~5 min after the first

        clock.advance(Duration.ofMinutes(56)); // > 60 min after the hour bucket was created
        assertThat(rateLimiter.tryAcquire(phone)).isTrue();
    }

    @Test
    @DisplayName("key is normalised — '237…' and '+237…' share one bucket")
    void shouldShareBucketAcrossPhoneFormats() {
        assertThat(rateLimiter.tryAcquire("237600000000")).isTrue();
        assertThat(rateLimiter.tryAcquire("+237 600 000 000")).isFalse();
    }

    @Test
    @DisplayName("different phones do not interfere")
    void differentPhonesIndependent() {
        assertThat(rateLimiter.tryAcquire(phone)).isTrue();
        assertThat(rateLimiter.tryAcquire("+237600000001")).isTrue();
    }

    @Test
    @DisplayName("expired buckets are evicted (bounded state)")
    void shouldEvictExpiredBuckets() {
        for (int i = 0; i < 50; i++) {
            rateLimiter.tryAcquire("+2376000001" + String.format("%02d", i));
        }
        assertThat(rateLimiter.trackedPhones()).isEqualTo(50);
        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertThat(rateLimiter.trackedPhones()).isZero();
    }
}
