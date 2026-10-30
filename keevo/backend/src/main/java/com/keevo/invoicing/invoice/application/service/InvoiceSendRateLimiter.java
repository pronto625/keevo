package com.keevo.invoicing.invoice.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-tenant cap on WhatsApp invoice sendings (Story 14.2): fixed one-hour window per tenant (Caffeine, ticker from
 * the injected {@link Clock}); past {@code keevo.invoicing.max-sends-per-hour} (30) → 429 {@code RATE_LIMIT_EXCEEDED}.
 * In-memory, one window per instance (like the export limiter).
 */
@Component
public class InvoiceSendRateLimiter {

    static final long MAX_TENANTS = 100_000;

    private final Cache<String, AtomicInteger> perHour;
    private final int maxPerHour;

    @Autowired
    public InvoiceSendRateLimiter(@Value("${keevo.invoicing.max-sends-per-hour:30}") int maxPerHour, Clock clock) {
        this.maxPerHour = maxPerHour;
        this.perHour = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(1))
                .maximumSize(MAX_TENANTS)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
    }

    /** Gives back the slot of a sending the provider refused (a failure never consumes the quota). */
    public void release(String tenantSchema) {
        AtomicInteger n = perHour.getIfPresent(tenantSchema);
        if (n != null) {
            n.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    /** Count one sending of {@code tenantSchema}. @throws DomainException RATE_LIMIT_EXCEEDED */
    public void acquire(String tenantSchema) {
        int n = perHour.get(tenantSchema, k -> new AtomicInteger()).incrementAndGet();
        if (n > maxPerHour) {
            throw new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many invoice sendings",
                    Map.of("limit", maxPerHour, "window", "PT1H"));
        }
    }
}
