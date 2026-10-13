package com.keevo.admin.revenue.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Pure revenue arithmetic over the payments and plan events of the non-deleted tenants (Story 9-3).
 * No I/O: the service loads the rows, this class applies the FR79 definitions (XAF integers, WAT calendar).
 *
 * <ul>
 *   <li>Monthly value of a payment = {@code amount × 30 / days(period_start..period_end inclusive)}, rounded
 *       half-up to the XAF.</li>
 *   <li>MRR on a day = sum of the monthly values of the payments covering that day, one per tenant (latest
 *       {@code paid_at} among those covering it). A payment stops counting from the WAT date of a later
 *       {@code PREMIUM → FREE} event of its tenant (manual downgrade before the paid period ends), until a
 *       later PREMIUM event.</li>
 *   <li>New paying tenant of a range = first-ever payment's {@code paid_at} (WAT date) in the range.</li>
 *   <li>Churned tenant of a range = last {@code PREMIUM → FREE} event (cause EXPIRY or ADMIN_DOWNGRADE) in the
 *       range with no later return to PREMIUM in the same range. Churn base = tenants paying the day before
 *       the range ∪ tenants churned in the range (so churned ⊆ base, rate ≤ 100 %).</li>
 *   <li>Plan end date of a churn = event date − 1 day for EXPIRY (the job runs the day after the last paid
 *       day), the event date for ADMIN_DOWNGRADE.</li>
 *   <li>Trial → Premium conversion of a range: denominator = tenants with a trial history (event to
 *       PREMIUM_TRIAL) whose trial ended (first event leaving PREMIUM_TRIAL, or first activation) in the range;
 *       numerator = those of them whose first PREMIUM activation (after the trial start) is also in the range
 *       (converted ⊆ trials ended).</li>
 *   <li>Tenure = months from the start of the latest continuous paid streak (payment periods overlapping or
 *       separated by at most {@value #MAX_STREAK_GAP_DAYS} unpaid day) to the plan end date.</li>
 * </ul>
 */
public final class RevenueMetrics {

    public static final ZoneId WAT = ZoneId.of("Africa/Douala");
    /** Average month length used for the tenure (365.25 / 12). */
    private static final double DAYS_PER_MONTH = 365.25 / 12;
    /** Unpaid days tolerated between two payment periods of the same paid streak. */
    static final int MAX_STREAK_GAP_DAYS = 1;

    static final String PREMIUM = "PREMIUM";
    static final String FREE = "FREE";
    static final String TRIAL = "PREMIUM_TRIAL";
    static final String EXPIRY = "EXPIRY";
    static final Set<String> CHURN_CAUSES = Set.of(EXPIRY, "ADMIN_DOWNGRADE");
    /** Correction of a stale public plan (9-2 realign job): not a plan change, ignored by churn and conversion. */
    static final String REALIGNMENT = "REALIGNMENT";

    private final List<RevenuePayment> payments;
    private final Map<UUID, List<PlanChange>> eventsByTenant;

    public RevenueMetrics(List<RevenuePayment> payments, List<PlanChange> events) {
        this.payments = List.copyOf(payments);
        Map<UUID, List<PlanChange>> grouped = new LinkedHashMap<>();
        events.stream()
                .sorted(Comparator.comparing(PlanChange::occurredAt))
                .forEach(e -> grouped.computeIfAbsent(e.tenantId(), k -> new ArrayList<>()).add(e));
        this.eventsByTenant = grouped;
    }

    // ── Payments ──────────────────────────────────────────────────────────────

    /** {@code amount × 30 / covered days}, rounded half-up to the XAF. */
    public static long monthlyValue(RevenuePayment p) {
        long days = ChronoUnit.DAYS.between(p.periodStart(), p.periodEnd()) + 1;
        if (days <= 0) return 0;
        return (p.amountXaf() * 60 + days) / (2 * days);
    }

    /** Per tenant, the payment counted on {@code day}: the latest paid among those covering it. */
    Map<UUID, RevenuePayment> countedPayments(LocalDate day) {
        Map<UUID, RevenuePayment> byTenant = new HashMap<>();
        for (RevenuePayment p : payments) {
            if (day.isBefore(p.periodStart()) || day.isAfter(p.periodEnd())) continue;
            if (downgradedSince(p, day)) continue;
            byTenant.merge(p.tenantId(), p, (a, b) -> b.paidAt().isAfter(a.paidAt()) ? b : a);
        }
        return byTenant;
    }

    /**
     * True when, on {@code day}, the tenant's latest plan event (WAT date ≤ day) is a PREMIUM → FREE made after
     * the payment: the payment no longer counts.
     */
    private boolean downgradedSince(RevenuePayment p, LocalDate day) {
        PlanChange last = null;
        for (PlanChange e : eventsByTenant.getOrDefault(p.tenantId(), List.of())) {
            if (watDate(e.occurredAt()).isAfter(day)) break;
            last = e;
        }
        return last != null && PREMIUM.equals(last.fromPlan()) && FREE.equals(last.toPlan())
                && last.occurredAt().isAfter(p.paidAt());
    }

    public long mrrAt(LocalDate day) {
        return countedPayments(day).values().stream().mapToLong(RevenueMetrics::monthlyValue).sum();
    }

    public int payingAt(LocalDate day) {
        return countedPayments(day).size();
    }

    /** Tenants counted in the MRR on {@code day}. */
    public Set<UUID> payingTenantsAt(LocalDate day) {
        return Set.copyOf(countedPayments(day).keySet());
    }

    /** Tenants whose first-ever payment was made (WAT date of {@code paid_at}) in {@code from..to}. */
    public Set<UUID> newPayingTenants(LocalDate from, LocalDate to) {
        Map<UUID, Instant> first = new HashMap<>();
        for (RevenuePayment p : payments) {
            first.merge(p.tenantId(), p.paidAt(), (a, b) -> b.isBefore(a) ? b : a);
        }
        Set<UUID> result = new HashSet<>();
        first.forEach((tenant, paidAt) -> {
            if (inRange(watDate(paidAt), from, to)) result.add(tenant);
        });
        return result;
    }

    /** Sum of the payments made (WAT date of {@code paid_at}) in {@code from..to}. */
    public long revenueCollected(LocalDate from, LocalDate to) {
        return payments.stream()
                .filter(p -> inRange(watDate(p.paidAt()), from, to))
                .mapToLong(RevenuePayment::amountXaf)
                .sum();
    }

    /**
     * Start of the latest continuous paid streak that began on or before {@code asOf}: payment periods
     * overlapping or separated by at most {@value #MAX_STREAK_GAP_DAYS} unpaid day belong to the same streak.
     */
    public Optional<LocalDate> streakStart(UUID tenantId, LocalDate asOf) {
        List<RevenuePayment> periods = payments.stream()
                .filter(p -> p.tenantId().equals(tenantId) && !p.periodStart().isAfter(asOf))
                .sorted(Comparator.comparing(RevenuePayment::periodStart))
                .toList();
        LocalDate start = null;
        LocalDate end = null;
        for (RevenuePayment p : periods) {
            if (start == null || p.periodStart().isAfter(end.plusDays(MAX_STREAK_GAP_DAYS + 1L))) {
                start = p.periodStart();
                end = p.periodEnd();
            } else if (p.periodEnd().isAfter(end)) {
                end = p.periodEnd();
            }
        }
        return Optional.ofNullable(start);
    }

    // ── Churn ─────────────────────────────────────────────────────────────────

    /** Tenant → churn event for the churns of {@code from..to}. */
    public Map<UUID, PlanChange> churned(LocalDate from, LocalDate to) {
        Map<UUID, PlanChange> result = new LinkedHashMap<>();
        eventsByTenant.forEach((tenant, events) -> {
            PlanChange lastChurn = null;
            for (PlanChange e : events) {
                if (REALIGNMENT.equals(e.cause())) continue;
                if (!inRange(watDate(e.occurredAt()), from, to)) continue;
                if (isChurn(e)) {
                    lastChurn = e;
                } else if (lastChurn != null && PREMIUM.equals(e.toPlan())) {
                    lastChurn = null; // came back to Premium within the range
                }
            }
            if (lastChurn != null) result.put(tenant, lastChurn);
        });
        return result;
    }

    /** Churn denominator: paying the day before {@code from} ∪ churned in the range. */
    public int churnBase(LocalDate from, LocalDate to) {
        Set<UUID> base = new HashSet<>(countedPayments(from.minusDays(1)).keySet());
        base.addAll(churned(from, to).keySet());
        return base.size();
    }

    /** Last day of the plan: event date − 1 for an EXPIRY (job runs the day after), event date otherwise. */
    public static LocalDate planEndDate(PlanChange churn) {
        LocalDate day = watDate(churn.occurredAt());
        return EXPIRY.equals(churn.cause()) ? day.minusDays(1) : day;
    }

    static boolean isChurn(PlanChange e) {
        return PREMIUM.equals(e.fromPlan()) && FREE.equals(e.toPlan()) && CHURN_CAUSES.contains(e.cause());
    }

    /** Ratio rounded to 4 decimals, 0 when the denominator is 0. */
    public static double rate(int numerator, int denominator) {
        if (denominator <= 0) return 0d;
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP)
                .doubleValue();
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    public record Conversion(int converted, int trialsEnded) {
        public double rate() { return RevenueMetrics.rate(converted, trialsEnded); }
    }

    public Conversion conversion(LocalDate from, LocalDate to) {
        int converted = 0;
        int ended = 0;
        for (List<PlanChange> events : eventsByTenant.values()) {
            PlanChange trialStart = null;
            PlanChange firstActivation = null;
            PlanChange trialEnd = null;
            for (PlanChange e : events) {
                if (REALIGNMENT.equals(e.cause())) continue;
                if (trialStart == null) {
                    if (TRIAL.equals(e.toPlan())) trialStart = e;
                    continue; // nothing before the trial start counts
                }
                if (firstActivation == null && PREMIUM.equals(e.toPlan())) firstActivation = e;
                if (trialEnd == null && (TRIAL.equals(e.fromPlan()) || PREMIUM.equals(e.toPlan()))) trialEnd = e;
            }
            if (trialEnd == null || !inRange(watDate(trialEnd.occurredAt()), from, to)) continue;
            ended++;
            if (firstActivation != null && inRange(watDate(firstActivation.occurredAt()), from, to)) converted++;
        }
        return new Conversion(converted, ended);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Months between two dates, 1 decimal (average month of 30.4375 days). */
    public static double monthsBetween(LocalDate start, LocalDate end) {
        long days = Math.max(0, ChronoUnit.DAYS.between(start, end));
        return BigDecimal.valueOf(days / DAYS_PER_MONTH).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    public static LocalDate watDate(Instant instant) {
        return LocalDate.ofInstant(instant, WAT);
    }

    private static boolean inRange(LocalDate day, LocalDate from, LocalDate to) {
        return !day.isBefore(from) && !day.isAfter(to);
    }
}
