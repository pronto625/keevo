package com.keevo.reporting.export.application.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.keevo.reporting.export.adapter.out.config.ExportProperties;
import com.keevo.reporting.export.domain.port.out.ExportJobRepository;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-tenant export rate limiting (Story v1s-18-1).
 *
 * <ul>
 *   <li>{@link #acquireRequest}: fixed one-hour window per tenant (Caffeine, ticker from the
 *       injected {@link Clock}); past {@code max-requests-per-hour} → 429.</li>
 *   <li>{@link #checkActiveJobs}: {@code max-active-jobs} PENDING/RUNNING jobs already → 429.</li>
 * </ul>
 * In-memory: one window per instance (the export endpoints are low-traffic, owner-only).
 */
@Component
public class ExportRateLimiter {

    static final long MAX_TENANTS = 100_000;

    private final Cache<String, AtomicInteger> perHour;
    private final int maxRequestsPerHour;
    private final int maxActiveJobs;
    private final ExportJobRepository jobRepository;

    @Autowired
    public ExportRateLimiter(ExportProperties properties, ExportJobRepository jobRepository, Clock clock) {
        this(properties.getMaxRequestsPerHour(), properties.getMaxActiveJobs(), jobRepository, clock);
    }

    public ExportRateLimiter(int maxRequestsPerHour, int maxActiveJobs, ExportJobRepository jobRepository,
                             Clock clock) {
        this.maxRequestsPerHour = maxRequestsPerHour;
        this.maxActiveJobs = maxActiveJobs;
        this.jobRepository = jobRepository;
        this.perHour = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(1))
                .maximumSize(MAX_TENANTS)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis()))
                .executor(Runnable::run)
                .build();
    }

    /** Count one export request of {@code tenantSchema}. @throws DomainException RATE_LIMIT_EXCEEDED */
    public void acquireRequest(String tenantSchema) {
        int n = perHour.get(tenantSchema, k -> new AtomicInteger()).incrementAndGet();
        if (n > maxRequestsPerHour) {
            throw new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many export requests",
                    Map.of("limit", maxRequestsPerHour, "window", "PT1H"));
        }
    }

    /** Current tenant (routed repository). @throws DomainException RATE_LIMIT_EXCEEDED */
    public void checkActiveJobs() {
        if (jobRepository.countActive() >= maxActiveJobs) {
            throw new DomainException(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many export jobs in progress",
                    Map.of("maxActiveJobs", maxActiveJobs));
        }
    }
}
