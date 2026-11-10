package com.keevo.mcp.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("21.9 — MCP rate limit: per token and per company, fixed 1-minute windows")
class McpRateLimiterTest {

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-10T10:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    @DisplayName("a token beyond its limit is refused; another token of the company keeps going until the company limit")
    void limits() {
        MutableClock clock = new MutableClock();
        McpRateLimiter limiter = new McpRateLimiter(clock, 3, 5, 2);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire(a, "kv_aaaaaa")).isTrue();
        }
        assertThat(limiter.tryAcquire(a, "kv_aaaaaa")).isFalse();
        assertThat(limiter.tryAcquire(b, "kv_aaaaaa")).isTrue();
        assertThat(limiter.tryAcquire(b, "kv_aaaaaa")).isTrue();
        assertThat(limiter.tryAcquire(b, "kv_aaaaaa")).isFalse(); // company: 5 used
        assertThat(limiter.tryAcquire(UUID.randomUUID(), "kv_bbbbbb")).isTrue(); // other company untouched

        clock.now = clock.now.plus(Duration.ofSeconds(61));
        assertThat(limiter.tryAcquire(a, "kv_aaaaaa")).isTrue();
    }

    @Test
    @DisplayName("failed authentications: braked per address after the limit, for one minute; no address = no brake")
    void failures() {
        MutableClock clock = new MutableClock();
        McpRateLimiter limiter = new McpRateLimiter(clock, 3, 5, 2);
        assertThat(limiter.tooManyFailures("1.2.3.4")).isFalse();
        limiter.recordFailure("1.2.3.4");
        limiter.recordFailure("1.2.3.4");
        assertThat(limiter.tooManyFailures("1.2.3.4")).isTrue();
        assertThat(limiter.tooManyFailures("5.6.7.8")).isFalse();
        limiter.recordFailure(null);
        assertThat(limiter.tooManyFailures(null)).isFalse();
        clock.now = clock.now.plus(Duration.ofSeconds(61));
        assertThat(limiter.tooManyFailures("1.2.3.4")).isFalse();
    }
}
