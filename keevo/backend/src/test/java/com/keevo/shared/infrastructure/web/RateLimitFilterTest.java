package com.keevo.shared.infrastructure.web;

import com.keevo.shared.infrastructure.persistence.TenantContext;
import com.keevo.shared.infrastructure.web.RateLimitFilter.RateLimitBucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RateLimitFilterTest — Unit tests for {@link RateLimitFilter} and {@link RateLimitBucket}.
 */
@DisplayName("RateLimitFilter")
class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter();

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ── RateLimitBucket ───────────────────────────────────────────────────────

    @Test
    @DisplayName("under 100 requests — all pass (isLimitExceeded returns false)")
    void bucket_under100Requests_allPass() {
        RateLimitBucket bucket = new RateLimitBucket();
        for (int i = 0; i < 100; i++) {
            assertThat(bucket.isLimitExceeded())
                    .as("Request #%d should NOT be rate limited", i + 1)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("at 101st request — limit exceeded (isLimitExceeded returns true)")
    void bucket_at101stRequest_exceedsLimit() {
        RateLimitBucket bucket = new RateLimitBucket();
        for (int i = 0; i < 100; i++) {
            bucket.isLimitExceeded(); // consume first 100
        }
        // 101st request
        assertThat(bucket.isLimitExceeded()).isTrue();
    }

    @Test
    @DisplayName("bucket count resets after 60 seconds (simulated)")
    void bucket_resetsAfterWindowExpiry() throws Exception {
        RateLimitBucket bucket = new RateLimitBucket();

        // Exhaust the limit
        for (int i = 0; i <= 100; i++) {
            bucket.isLimitExceeded();
        }
        assertThat(bucket.isLimitExceeded()).isTrue();

        // Simulate window expiry by setting windowStart to 61 seconds ago
        java.lang.reflect.Field windowField = RateLimitBucket.class
                .getDeclaredField("windowStart");
        windowField.setAccessible(true);
        windowField.set(bucket, System.currentTimeMillis() - 61_000L);

        // After reset, should accept again
        assertThat(bucket.isLimitExceeded()).isFalse();
    }

    // ── RateLimitFilter ───────────────────────────────────────────────────────

    @Test
    @DisplayName("no tenantId — filter passes through without rate limiting")
    void filter_noTenantId_passesThrough() throws Exception {
        // TenantContext is clear (no tenant)
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isNotEqualTo(429);
    }

    @Test
    @DisplayName("different tenants have isolated counters")
    void bucket_differentTenants_haveIsolatedCounters() {
        // Two separate buckets (one per tenant in the filter's ConcurrentHashMap)
        RateLimitBucket tenant1 = new RateLimitBucket();
        RateLimitBucket tenant2 = new RateLimitBucket();

        // Exhaust tenant1's limit
        for (int i = 0; i <= 100; i++) {
            tenant1.isLimitExceeded();
        }

        // tenant2 should still be unaffected
        assertThat(tenant1.isLimitExceeded()).isTrue();
        assertThat(tenant2.isLimitExceeded()).isFalse();
    }

    @Test
    @DisplayName("returns HTTP 429 with RATE_LIMIT_EXCEEDED when limit exceeded")
    void filter_returns429_whenLimitExceeded() throws Exception {
        TenantContext.setCurrentTenant("kv_abc123");

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // Send 101 requests to exhaust the limit
        for (int i = 0; i <= 100; i++) {
            response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, chain);
        }

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
    }
}
