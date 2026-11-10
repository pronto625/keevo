package com.keevo.mcp.server;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Story 21.9 — brake on the MCP server: at most {@code keevo.mcp.rate-limit.token-per-minute} requests per token and
 * {@code keevo.mcp.rate-limit.tenant-per-minute} per company (all its tokens), one fixed 1-minute window per key
 * (bounded Caffeine caches with the {@link Clock} ticker, like {@code AppVersionCheckRateLimiter}). O(1) per request;
 * counters are per instance (single-instance deployment, like the other limiters). The per-company limit of every API
 * request ({@code RateLimitFilter}) applies on top.
 */
@Component
public class McpRateLimiter {

    static final long MAX_ENTRIES = 100_000;

    private final Cache<UUID, AtomicInteger> perToken;
    private final Cache<String, AtomicInteger> perTenant;
    private final Cache<String, AtomicInteger> failuresPerIp;
    private final int tokenMax;
    private final int tenantMax;
    private final int failuresMax;

    public McpRateLimiter(Clock clock,
                          @Value("${keevo.mcp.rate-limit.token-per-minute:30}") int tokenMax,
                          @Value("${keevo.mcp.rate-limit.tenant-per-minute:60}") int tenantMax,
                          @Value("${keevo.mcp.rate-limit.failures-per-ip-per-minute:20}") int failuresMax) {
        this.perToken = window(clock);
        this.perTenant = window(clock);
        this.failuresPerIp = window(clock);
        this.tokenMax = tokenMax;
        this.tenantMax = tenantMax;
        this.failuresMax = failuresMax;
    }

    /**
     * Whether this IP already failed authentication too often this minute (checked before any database lookup, so a
     * flood of unknown tokens costs nothing). Null or blank IP = not limited.
     */
    public boolean tooManyFailures(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }
        AtomicInteger failures = failuresPerIp.getIfPresent(ip);
        return failures != null && failures.get() >= failuresMax;
    }

    /** Counts one failed authentication of this IP. */
    public void recordFailure(String ip) {
        if (ip != null && !ip.isBlank()) {
            failuresPerIp.get(ip, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    private static <K> Cache<K, AtomicInteger> window(Clock clock) {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .maximumSize(MAX_ENTRIES)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
    }

    /**
     * Counts one request of the token in the company; {@code false} when either limit is reached — then nothing is
     * counted (a request refused by the company limit no longer uses up its token's window, 21.4.2 review).
     */
    public boolean tryAcquire(UUID tokenId, String tenantSchema) {
        AtomicInteger token = perToken.get(tokenId, k -> new AtomicInteger());
        AtomicInteger tenant = perTenant.get(tenantSchema, k -> new AtomicInteger());
        synchronized (tenant) {
            if (token.get() >= tokenMax || tenant.get() >= tenantMax) {
                return false;
            }
            token.incrementAndGet();
            tenant.incrementAndGet();
            return true;
        }
    }
}
