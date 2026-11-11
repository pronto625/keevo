package com.keevo.clientportal;

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
        PortalRateLimiter limiter = new PortalRateLimiter(clock, 3, 2);
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
