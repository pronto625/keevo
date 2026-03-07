package com.keevo.shared.infrastructure.web;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RateLimitFilter — Per-tenant request rate limiting (100 req/min).
 *
 * <p>Runs AFTER {@code JwtAuthFilter} ({@code @Order(1)}) so that
 * {@link TenantContext} is already set when this filter executes.
 *
 * <p>Design:
 * <ul>
 *   <li>In-memory {@link ConcurrentHashMap} keyed by {@code tenantId} — OK for MVP (single instance)</li>
 *   <li>Each bucket: {@link AtomicInteger} count + volatile window start timestamp</li>
 *   <li>Window resets automatically after 60 seconds (lazy reset on next request)</li>
 *   <li>Unauthenticated requests (no tenantId in context) pass through unthrottled</li>
 * </ul>
 *
 * <p>Phase 2 (multi-instance): replace with Redis {@code spring-data-redis} + Lua script
 * behind a {@code RateLimitPort} interface to abstract the backing store.
 *
 * @see TenantContext
 */
@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    static final int MAX_REQUESTS_PER_MINUTE = 100;

    private final ConcurrentHashMap<String, RateLimitBucket> buckets =
            new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null) {
            // Public / unauthenticated endpoint — no rate limiting
            chain.doFilter(request, response);
            return;
        }

        RateLimitBucket bucket = buckets.computeIfAbsent(tenantId, k -> new RateLimitBucket());

        if (bucket.isLimitExceeded()) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                    {"error":"Rate limit exceeded","domainCode":"RATE_LIMIT_EXCEEDED",\
                    "details":{"limitPerMinute":100,"resetInSeconds":60}}""");
            return;
        }

        chain.doFilter(request, response);
    }

    // ── Inner class: rate limit bucket per tenant ─────────────────────────────

    /**
     * RateLimitBucket — Thread-safe per-tenant request counter with sliding window reset.
     *
     * <p>Window start is updated lazily on the first request after the 60-second window
     * expires. This avoids a background thread while still being accurate enough for MVP.
     */
    static class RateLimitBucket {

        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();

        /**
         * Increment count and return {@code true} if the limit has been exceeded.
         *
         * <p>Synchronized on the bucket instance to prevent race conditions
         * where two threads simultaneously detect window expiry and both reset
         * the counter, allowing burst requests to slip through undercounted.
         * (M1 fix — race condition in sliding window reset)
         */
        synchronized boolean isLimitExceeded() {
            long now = System.currentTimeMillis();
            if (now - windowStart > 60_000L) {
                // Window expired — reset counter and start new window
                count.set(0);
                windowStart = now;
            }
            return count.incrementAndGet() > MAX_REQUESTS_PER_MINUTE;
        }
    }
}
