package com.keevo.admin.revenue.domain.model;

import com.keevo.identity.api.PlanKind;

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
 * Story 19.7a: {@code PREMIUM} / {@code FREE} / {@code PREMIUM_TRIAL} below read « a plan of kind PAID / FREE /
 * TRIAL » (plan catalogue), so a plan created as data counts like the seeded one of its kind.
 *
 * <ul>
 *   <li>Monthly value of a payment = {@code (amount + credit part) × 30 / days(period_start..period_end inclusive)},
 *       rounded half-up to the XAF (Story 24.5: a plan paid with the account credit counts in the MRR; the revenue
 *       collected counts the cash only).</li>
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

    // Story 19.7a: « Premium » / « Free » / « trial » below are plan kinds (PAID / FREE / TRIAL), never plan codes.
    static final PlanKind PREMIUM = PlanKind.PAID;
    static final PlanKind FREE = PlanKind.FREE;
    static final PlanKind TRIAL = PlanKind.TRIAL;
    static final String EXPIRY = "EXPIRY";
    static final Set<String> CHURN_CAUSES = Set.of(EXPIRY, "ADMIN_DOWNGRADE");
    /** Correction of a stale public plan (9-2 realign job): not a plan change, ignored by churn and conversion. */
    static final String REALIGNMENT = "REALIGNMENT";
    /** Story 19.7b — one-shot data migration of the merged offer (PREMIUM → TEAM): not a plan change either. */
    static final String PLAN_MIGRATION = "PLAN_MIGRATION";
    /** Story 24.2 — a tenant imported from Bref: a data migration, not a commercial change. */
    static final String MIGRATION_BREF = "MIGRATION_BREF";

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
        return (p.valueXaf() * 60 + days) / (2 * days);
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
        return last != null && PREMIUM == last.fromKind() && FREE == last.toKind()
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

    /**
     * Story 21.10 — MRR and paying tenants on {@code day}, and revenue collected in {@code from..to}, split by
     * (plan, cycle) of the payment (same definitions as {@link #mrrAt}, {@link #payingAt} and
     * {@link #revenueCollected}). Keys are {@code [planCode, cycleCode]}, either {@code null} when unknown. Cost: that
     * of {@link #countedPayments} — O(payments × plan events of the tenant) — plus O(payments).
     */
    public Map<List<String>, Totals> byPlanAndCycle(LocalDate day, LocalDate from, LocalDate to) {
        Map<List<String>, Totals> totals = new LinkedHashMap<>();
        for (RevenuePayment p : countedPayments(day).values()) {
            totals.computeIfAbsent(key(p), k -> new Totals()).addCounted(monthlyValue(p));
        }
        for (RevenuePayment p : payments) {
            // Story 24.5: a plan paid 100 % with the credit collects nothing (counted in the MRR above only)
            if (p.amountXaf() > 0 && inRange(watDate(p.paidAt()), from, to)) {
                totals.computeIfAbsent(key(p), k -> new Totals()).addCollected(p.amountXaf());
            }
        }
        return totals;
    }

    private static List<String> key(RevenuePayment p) {
        return Arrays.asList(p.planCode(), p.cycleCode()); // nulls allowed (unknown plan / free-form cycle)
    }

    /** Story 21.10 — running totals of one (plan, cycle). */
    public static final class Totals {
        private long mrr;
        private int paying;
        private long collected;
        private int payments;

        void addCounted(long monthly) {
            mrr += monthly;
            paying++;
        }

        void addCollected(long amount) {
            collected += amount;
            payments++;
        }

        public long mrr() { return mrr; }
        public int paying() { return paying; }
        public long collected() { return collected; }
        public int payments() { return payments; }
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
                if (notAPlanChange(e)) continue;
                if (!inRange(watDate(e.occurredAt()), from, to)) continue;
                if (isChurn(e)) {
                    lastChurn = e;
                } else if (lastChurn != null && PREMIUM == e.toKind()) {
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

    /** A correction or a data migration (19.7b offer, 24.2 Bref import): ignored by churn and conversion. */
    static boolean notAPlanChange(PlanChange e) {
        return REALIGNMENT.equals(e.cause()) || PLAN_MIGRATION.equals(e.cause()) || MIGRATION_BREF.equals(e.cause());
    }

    static boolean isChurn(PlanChange e) {
        return PREMIUM == e.fromKind() && FREE == e.toKind() && CHURN_CAUSES.contains(e.cause());
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
                if (notAPlanChange(e)) continue;
                if (trialStart == null) {
                    if (TRIAL == e.toKind()) trialStart = e;
                    continue; // nothing before the trial start counts
                }
                if (firstActivation == null && PREMIUM == e.toKind()) firstActivation = e;
                if (trialEnd == null && (TRIAL == e.fromKind() || PREMIUM == e.toKind())) trialEnd = e;
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
