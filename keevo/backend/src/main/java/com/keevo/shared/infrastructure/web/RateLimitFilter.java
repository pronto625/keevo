package com.keevo.shared.infrastructure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RateLimitFilter — Per-tenant request rate limiting (keevo.rate-limit.requests-per-minute, default 300 req/min).
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

    /**
     * Story 22.19 (D15) — default per-tenant ceiling, property {@code keevo.rate-limit.requests-per-minute} (was a
     * hardcoded 100, refused 43 requests of the RN app in 25 min).
     */
    static final int DEFAULT_REQUESTS_PER_MINUTE = 300;

    private final ConcurrentHashMap<String, RateLimitBucket> buckets =
            new ConcurrentHashMap<>();
    private final int maxRequestsPerMinute;
    private final String rateLimitBody;

    /** Default ceiling — tests. */
    public RateLimitFilter() {
        this(DEFAULT_REQUESTS_PER_MINUTE);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RateLimitFilter(
            @org.springframework.beans.factory.annotation.Value(
                    "${keevo.rate-limit.requests-per-minute:" + DEFAULT_REQUESTS_PER_MINUTE + "}")
            int maxRequestsPerMinute) {
        if (maxRequestsPerMinute < 1) {
            throw new IllegalArgumentException("keevo.rate-limit.requests-per-minute must be >= 1");
        }
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.rateLimitBody = rateLimitBody(maxRequestsPerMinute);
    }

    int maxRequestsPerMinute() {
        return maxRequestsPerMinute;
    }

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

        RateLimitBucket bucket = buckets.computeIfAbsent(tenantId, k -> new RateLimitBucket(maxRequestsPerMinute));

        if (bucket.isLimitExceeded()) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            // Story 16.14 — French text from the shared catalogue
            response.getWriter().write(rateLimitBody);
            return;
        }

        chain.doFilter(request, response);
    }

    /** Serialised once by Jackson (escaping handled); the constant is only a never-expected fallback. */
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String FALLBACK_BODY = "{\"error\":\"Trop de requêtes, veuillez patienter\","
            + "\"domainCode\":\"RATE_LIMIT_EXCEEDED\",\"details\":{\"resetInSeconds\":60}}";

    static String rateLimitBody(int limitPerMinute) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ErrorMessages.of("RATE_LIMIT_EXCEEDED", "Trop de requêtes, veuillez patienter"));
        body.put("domainCode", "RATE_LIMIT_EXCEEDED");
        body.put("details", Map.of("limitPerMinute", limitPerMinute, "resetInSeconds", 60));
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return FALLBACK_BODY;
        }
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
        private final int limit;

        RateLimitBucket() {
            this(DEFAULT_REQUESTS_PER_MINUTE);
        }

        RateLimitBucket(int limit) {
            this.limit = limit;
        }

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
            return count.incrementAndGet() > limit;
        }
    }
}
