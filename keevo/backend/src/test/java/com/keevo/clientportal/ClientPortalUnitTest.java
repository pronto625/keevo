package com.keevo.clientportal;

import com.keevo.clientportal.auth.application.PortalAuthService;
import com.keevo.clientportal.auth.application.PortalRateLimiter;
import com.keevo.clientportal.auth.domain.model.PortalTokens;
import com.keevo.clientportal.invoice.domain.model.PortalCursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Client portal — tokens, cursor, rate limits (21.7)")
class ClientPortalUnitTest {

    @Test
    @DisplayName("token: kvc_ + 43 base64url chars, unique, 32-byte hash; shape check refuses anything else")
    void tokens() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String t = PortalTokens.generate();
            assertThat(t).startsWith("kvc_").hasSize(PortalTokens.LENGTH);
            assertThat(PortalTokens.looksLikeToken(t)).isTrue();
            assertThat(PortalTokens.hash(t)).hasSize(32);
            assertThat(seen.add(t)).isTrue();
        }
        assertThat(PortalTokens.looksLikeToken(null)).isFalse();
        assertThat(PortalTokens.looksLikeToken("kvp_" + "A".repeat(43))).isFalse(); // MCP token
        assertThat(PortalTokens.looksLikeToken("kvc_" + "A".repeat(42) + "=")).isFalse();
        assertThat(PortalTokens.looksLikeToken("kvc_" + "A".repeat(44))).isFalse();
        assertThat(PortalTokens.looksLikeToken("eyJhbGciOiJSUzI1NiJ9.e30.sig")).isFalse(); // a JWT
    }

    @Test
    @DisplayName("cursor: round trip to the microsecond, opaque, garbage refused")
    void cursor() {
        PortalCursor c = new PortalCursor(Instant.parse("2026-11-10T08:15:30.123456Z"), UUID.randomUUID(),
                UUID.randomUUID());
        String token = c.encode();
        assertThat(token).doesNotContain(c.tenantId().toString()).hasSizeLessThan(64);
        assertThat(PortalCursor.decode(token)).isEqualTo(c);
        assertThat(PortalCursor.decode(null)).isNull();
        assertThat(PortalCursor.decode(" ")).isNull();
        assertThatThrownBy(() -> PortalCursor.decode("not-a-cursor")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PortalCursor.decode("A".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PortalCursor.decode("####")).isInstanceOf(IllegalArgumentException.class);
        String forged = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(java.nio.ByteBuffer.allocate(40).putLong(Long.MIN_VALUE).array());
        assertThatThrownBy(() -> PortalCursor.decode(forged)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rate limits: per session per minute, failed authentications per IP; a new minute resets")
    void rateLimits() {
        MutableClock clock = new MutableClock();
        PortalRateLimiter limiter = new PortalRateLimiter(clock, 3, 2, 30, 10);
        assertThat(limiter.tryAcquire("s1")).isTrue();
        assertThat(limiter.tryAcquire("s1")).isTrue();
        assertThat(limiter.tryAcquire("s1")).isTrue();
        assertThat(limiter.tryAcquire("s1")).isFalse();
        assertThat(limiter.tryAcquire("s2")).isTrue();

        assertThat(limiter.tooManyFailures("1.2.3.4")).isFalse();
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        assertThat(limiter.tooManyFailures("1.2.3.4")).isTrue();
        assertThat(limiter.tooManyFailures("5.6.7.8")).isFalse();
        assertThat(limiter.tooManyFailures(null)).isFalse();

        clock.now = clock.now.plusSeconds(61);
        assertThat(limiter.tryAcquire("s1")).isTrue();
        assertThat(limiter.tooManyFailures("1.2.3.4")).isFalse();
    }

    @Test
    @DisplayName("reprise 11/11: Bref's login brake — per IP and per identifier, every attempt counted, 15-minute window")
    void loginBrake() {
        MutableClock clock = new MutableClock();
        PortalRateLimiter limiter = new PortalRateLimiter(clock, 60, 30, 3, 2);
        assertThat(limiter.tryLogin("1.1.1.1", "+237677000111")).isTrue();
        assertThat(limiter.tryLogin("1.1.1.1", "+237677000111")).isTrue();
        assertThat(limiter.tryLogin("2.2.2.2", "+237677000111")).as("3rd attempt on one identifier").isFalse();
        assertThat(limiter.tryLogin("1.1.1.1", "client@example.com")).as("3rd attempt from one IP").isTrue();
        assertThat(limiter.tryLogin("1.1.1.1", "autre@example.com")).as("4th attempt from one IP").isFalse();
        assertThat(limiter.tryLogin(null, "neuf@example.com")).as("no IP: identifier only").isTrue();

        clock.now = clock.now.plusSeconds(14 * 60);
        assertThat(limiter.tryLogin("3.3.3.3", "+237677000111")).as("still inside the 15 minutes").isFalse();
        clock.now = clock.now.plusSeconds(61);
        assertThat(limiter.tryLogin("1.1.1.1", "+237677000111")).as("window reset").isTrue();
    }

    @Test
    @DisplayName("reprise 11/11: sign-in identifier — e-mail lower-cased, phone in E.164 (+237 for 9 digits), else none")
    void identifierKeys() {
        assertThat(PortalAuthService.identifierKey(" Client@Example.COM ")).isEqualTo("client@example.com");
        assertThat(PortalAuthService.identifierKey("677 00 01 11")).isEqualTo("+237677000111");
        assertThat(PortalAuthService.identifierKey("+237677000111")).isEqualTo("+237677000111");
        assertThat(PortalAuthService.identifierKey("pas un numéro")).isNull();
        assertThat(PortalAuthService.identifierKey("a@b")).isNull();
        assertThat(PortalAuthService.identifierKey(" ")).isNull();
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-10T08:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
