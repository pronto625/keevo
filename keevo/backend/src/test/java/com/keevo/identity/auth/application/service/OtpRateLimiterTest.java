package com.keevo.identity.auth.application.service;

import com.keevo.shared.MutableTestClock;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OtpRateLimiter (Story 19.2)")
class OtpRateLimiterTest {

    MutableTestClock clock = new MutableTestClock(Instant.parse("2026-11-04T10:00:00Z"));
    OtpRateLimiter limiter = new OtpRateLimiter(clock, 3, 5, 10, 30);

    private void assertLimited(String identifier, String ip) {
        assertThatThrownBy(() -> limiter.acquire(identifier, ip))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                        .isEqualTo(ErrorCode.OTP_TOO_MANY_REQUESTS));
    }

    @Test
    @DisplayName("4th request in 10 min on one identifier → OTP_TOO_MANY_REQUESTS")
    void tenMinuteWindow() {
        for (int i = 0; i < 3; i++) limiter.acquire("+237699001122", "1.1.1." + i);
        assertLimited("+237699001122", "1.1.1.9");
    }

    @Test
    @DisplayName("10-minute window reopens, the hourly one caps at 5")
    void hourWindow() {
        for (int i = 0; i < 3; i++) limiter.acquire("a@ex.com", null);
        clock.advance(Duration.ofMinutes(11));
        limiter.acquire("a@ex.com", null);
        limiter.acquire("a@ex.com", null);
        assertLimited("a@ex.com", null);
        clock.advance(Duration.ofMinutes(50));
        assertThatCode(() -> limiter.acquire("a@ex.com", null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("11th request in 1 h from one IP → OTP_TOO_MANY_REQUESTS, whatever the identifier")
    void ipWindow() {
        for (int i = 0; i < 10; i++) limiter.acquire("user" + i + "@ex.com", "10.0.0.1");
        assertLimited("other@ex.com", "10.0.0.1");
        assertThatCode(() -> limiter.acquire("other2@ex.com", "10.0.0.2")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("31st code check in 1 h from one IP → OTP_TOO_MANY_REQUESTS; other IPs and no IP unaffected")
    void verifyIpWindow() {
        for (int i = 0; i < 30; i++) limiter.acquireVerify("10.0.0.7");
        assertThatThrownBy(() -> limiter.acquireVerify("10.0.0.7"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).getErrorCode())
                        .isEqualTo(ErrorCode.OTP_TOO_MANY_REQUESTS));
        assertThatCode(() -> limiter.acquireVerify("10.0.0.8")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquireVerify(null)).doesNotThrowAnyException();
        clock.advance(Duration.ofMinutes(61));
        assertThatCode(() -> limiter.acquireVerify("10.0.0.7")).doesNotThrowAnyException();
    }
}
