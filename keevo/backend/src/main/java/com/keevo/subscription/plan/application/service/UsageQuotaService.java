package com.keevo.subscription.plan.application.service;

import com.keevo.shared.capability.entitlement.PlanCatalog;
import com.keevo.shared.capability.entitlement.PlanQuota;
import com.keevo.shared.capability.entitlement.QuotaPeriod;
import com.keevo.shared.capability.entitlement.UsageCredits;
import com.keevo.shared.capability.entitlement.UsageMetric;
import com.keevo.shared.capability.entitlement.UsageQuotaPort;
import com.keevo.shared.capability.entitlement.UsageStatus;
import com.keevo.shared.capability.entitlement.UsageStatus.QuotaUsage;
import com.keevo.shared.domain.exception.DomainException;
import com.keevo.shared.domain.exception.ErrorCode;
import com.keevo.shared.domain.time.KeevoZones;
import com.keevo.shared.infrastructure.security.CurrentActor;
import com.keevo.subscription.plan.domain.port.out.UsageCounterStore;
import com.keevo.subscription.plan.domain.port.out.UsageCounterStore.TenantQuotas;
import com.keevo.subscription.plan.domain.port.out.UsageCounterStore.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/**
 * Story 19.8 — usage counters and plan quotas ({@link UsageQuotaPort}) and packs ({@link UsageCredits}).
 *
 * <p><b>Pattern:</b> guarded atomic upsert (optimistic, set-based) instead of read-check-write — concurrent devices
 * can never overshoot a quota and no lock is held across calls. Quotas are data rows (strategy by metric and
 * period, ADR-05); the fallback is a chain: plan quota, then pack, then refusal.
 *
 * <p><b>What is counted:</b> MONTH and DAY always (a guarded upsert when the plan limits the period, unguarded
 * otherwise), so « Mon plan » shows the monthly use and a quota added later or a plan changed mid-month starts from
 * the real figure; LIFETIME only under a LIFETIME quota (Bref: FREE's lifetime SMS).
 *
 * <p><b>Cost of an increment:</b> one indexed read (the tenant's plan and its quota rows for the metric, primary
 * keys), two or three upserts (primary key), at most one pack update; a refusal adds one primary-key read for its
 * {@code used} detail. Periods are those of {@link KeevoZones#WAT} at the injected {@link Clock}.
 *
 * <p><b>Pack journal (Story 21.3):</b> a pack-paid increment journals the units taken, the exhausted period and the
 * actor of the request ({@code null} for a scheduled or asynchronous send) in the same statement as the debit; a pack
 * credit journals its source. {@link #status} adds the units consumed from packs this month (same single query).
 *
 * <p>The tenant's current plan is {@code public.tenants.plan_type}: the global plan view every plan change updates in
 * its own transaction (Story 9-2, {@code PlanChangeRecorder}); its French name comes from the catalogue. A missing
 * plan code or one the catalogue does not know gets the free plan's quotas (WARN), like {@code PlanLimitGuard}
 * treats an unknown plan as free.
 */
@Service
public class UsageQuotaService implements UsageQuotaPort, UsageCredits {

    private static final Logger log = LoggerFactory.getLogger(UsageQuotaService.class);

    private final UsageCounterStore store;
    private final PlanCatalog catalog;
    private final Clock clock;

    public UsageQuotaService(UsageCounterStore store, PlanCatalog catalog, Clock clock) {
        this.store = store;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertAndIncrement(UUID tenantId, UsageMetric metric, int n) {
        requireArguments(metric, n);
        increment(resolve(store.quotasById(tenantId, metric), metric), metric, n);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertAndIncrementForSchema(String tenantSchema, UsageMetric metric, int n) {
        requireArguments(metric, n);
        increment(resolve(store.quotasBySchema(tenantSchema, metric), metric), metric, n);
    }

    @Override
    @Transactional(readOnly = true)
    public UsageStatus status(UUID tenantId) {
        return store.quotasById(tenantId, null).map(t -> status(withKnownPlan(t, null))).orElseGet(UsageStatus::empty);
    }

    @Override
    @Transactional(readOnly = true)
    public UsageStatus statusForSchema(String tenantSchema) {
        return store.quotasBySchema(tenantSchema, null).map(t -> status(withKnownPlan(t, null)))
                .orElseGet(UsageStatus::empty);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long add(UUID tenantId, UsageMetric metric, int n, Grant grant) {
        requireArguments(metric, n);
        if (grant == null) {
            throw new IllegalArgumentException("grant is required");
        }
        return store.addCredits(tenantId, metric, n, grant);
    }

    // ── increment ─────────────────────────────────────────────────────────────

    private TenantQuotas resolve(Optional<TenantQuotas> found, UsageMetric metric) {
        return withKnownPlan(found.orElseThrow(UsageQuotaService::tenantNotFound), metric);
    }

    /** A missing or non-catalogue plan code: the free plan's quotas apply (fail closed, WARN). */
    private TenantQuotas withKnownPlan(TenantQuotas tenant, UsageMetric metric) {
        if (tenant.planCode() != null && catalog.find(tenant.planCode()).isPresent()) {
            return tenant;
        }
        String free = catalog.freePlan().code();
        log.warn("Usage quotas: tenant {} has plan {} unknown to the catalogue — free plan {} quotas applied",
                tenant.tenantId(), tenant.planCode(), free);
        return new TenantQuotas(tenant.tenantId(), free, store.quotasOfPlan(free, metric));
    }

    private void increment(TenantQuotas tenant, UsageMetric metric, int n) {
        LocalDate today = today();
        Map<QuotaPeriod, Integer> limits = limitsOf(tenant.quotas(), metric);
        boolean paidByCredits = false;
        for (QuotaPeriod period : QuotaPeriod.CHECK_ORDER) {
            Integer limit = limits.get(period);
            if (period == QuotaPeriod.LIFETIME && limit == null) {
                continue; // LIFETIME is counted only under a LIFETIME quota
            }
            String key = period.key(today);
            // MONTH and DAY always counted: guarded under a limit, unguarded otherwise (never refused then)
            OptionalLong counted = store.increment(tenant.tenantId(), metric, period, key, n, limit);
            if (counted.isPresent()) {
                continue;
            }
            // exhausted: the DAY cap is never paid from a pack; MONTH / LIFETIME fall back on it (once per increment)
            if (period.payableFromCredits()
                    && (paidByCredits || store.consumeCredits(tenant.tenantId(), metric, n, period,
                    CurrentActor.actorIdOrNull()))) {
                paidByCredits = true;
                continue;
            }
            throw exceeded(tenant, metric, period, limit, store.used(tenant.tenantId(), metric, key), today);
        }
    }

    private DomainException exceeded(TenantQuotas tenant, UsageMetric metric, QuotaPeriod period, int limit,
                                     long used, LocalDate today) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("metric", metric.name());
        details.put("metricLabel", metric.label());
        details.put("period", period.name());
        details.put("periodLabel", period.label());
        details.put("limit", limit);
        details.put("used", used);
        Instant resetAt = period.resetAt(today, KeevoZones.WAT);
        details.put("resetAt", resetAt != null ? resetAt.toString() : null);
        details.put("planCode", tenant.planCode());
        details.put("planLabel", catalog.label(tenant.planCode(), tenant.planCode()));
        return new DomainException(ErrorCode.QUOTA_EXCEEDED, metric + " " + period + " quota reached", details);
    }

    // ── status ────────────────────────────────────────────────────────────────

    private UsageStatus status(TenantQuotas tenant) {
        LocalDate today = today();
        Set<String> keys = new LinkedHashSet<>();
        for (QuotaPeriod period : QuotaPeriod.values()) {
            keys.add(period.key(today));
        }
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(KeevoZones.WAT).toInstant();
        Usage usage = store.usage(tenant.tenantId(), keys, monthStart);
        List<QuotaUsage> quotas = new ArrayList<>();
        for (UsageMetric metric : UsageMetric.values()) {
            Map<QuotaPeriod, Integer> limits = limitsOf(tenant.quotas(), metric);
            Map<String, Long> counters = usage.counters().getOrDefault(metric, Map.of());
            // MONTH always (counted with or without a limit); DAY and LIFETIME when the plan limits them
            for (QuotaPeriod period : QuotaPeriod.values()) {
                if (period == QuotaPeriod.MONTH || limits.containsKey(period)) {
                    quotas.add(usage(metric, period, limits.get(period), counters, today));
                }
            }
        }
        return new UsageStatus(quotas, usage.credits(), usage.packsConsumed());
    }

    private static QuotaUsage usage(UsageMetric metric, QuotaPeriod period, Integer limit, Map<String, Long> counters,
                                    LocalDate today) {
        long used = counters.getOrDefault(period.key(today), 0L);
        Long remaining = limit == null ? null : Math.max(0L, limit - used);
        return new QuotaUsage(metric, period, limit, used, remaining, period.resetAt(today, KeevoZones.WAT));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Map<QuotaPeriod, Integer> limitsOf(List<PlanQuota> quotas, UsageMetric metric) {
        Map<QuotaPeriod, Integer> limits = new EnumMap<>(QuotaPeriod.class);
        for (PlanQuota q : quotas) {
            if (q.metric() == metric) {
                limits.put(q.period(), q.limit());
            }
        }
        return limits;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), KeevoZones.WAT);
    }

    private static void requireArguments(UsageMetric metric, int n) {
        if (metric == null) {
            throw new IllegalArgumentException("metric is required");
        }
        if (n < 1) {
            throw new IllegalArgumentException("n must be >= 1");
        }
    }

    private static DomainException tenantNotFound() {
        return new DomainException(ErrorCode.TENANT_NOT_FOUND, "Tenant not found");
    }
}
