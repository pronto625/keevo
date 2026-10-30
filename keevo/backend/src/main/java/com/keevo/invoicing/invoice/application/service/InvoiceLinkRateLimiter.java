package com.keevo.invoicing.invoice.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cap on the public invoice link (Story 14.2): the JWT-free endpoint renders a PDF on every hit, so each token may
 * be opened at most {@link #MAX_PER_WINDOW} times per {@link #WINDOW} (fixed window per token, Caffeine, in memory,
 * one window per instance) → 429 {@code INVOICE_LINK_RATE_LIMITED}.
 */
@Component
public class InvoiceLinkRateLimiter {

    public static final int MAX_PER_WINDOW = 30;
    public static final Duration WINDOW = Duration.ofMinutes(10);
    static final long MAX_TOKENS = 100_000;

    private final Cache<String, AtomicInteger> perToken;
    private final int max;

    @Autowired
    public InvoiceLinkRateLimiter(Clock clock) {
        this(MAX_PER_WINDOW, clock);
    }

    public InvoiceLinkRateLimiter(int max, Clock clock) {
        this.max = max;
        this.perToken = Caffeine.newBuilder()
                .expireAfterWrite(WINDOW)
                .maximumSize(MAX_TOKENS)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
    }

    /** Count one opening of {@code token}. @throws DomainException INVOICE_LINK_RATE_LIMITED */
    public void acquire(String token) {
        int n = perToken.get(token, k -> new AtomicInteger()).incrementAndGet();
        if (n > max) {
            throw new DomainException(ErrorCode.INVOICE_LINK_RATE_LIMITED, "Too many openings of an invoice link");
        }
    }
}
