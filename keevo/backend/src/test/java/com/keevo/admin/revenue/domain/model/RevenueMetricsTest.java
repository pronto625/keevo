package com.keevo.admin.revenue.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RevenueMetrics (9-3 definitions)")
class RevenueMetricsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 13);
    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID D = UUID.randomUUID();

    private static RevenuePayment pay(UUID tenant, long amount, String paidAt, LocalDate start, LocalDate end) {
        return new RevenuePayment(tenant, amount, Instant.parse(paidAt), start, end);
    }

    private static PlanChange event(UUID tenant, String from, String to, String cause, String at) {
        return new PlanChange(tenant, from, to, cause, Instant.parse(at));
    }

    // ── Monthly value / MRR ───────────────────────────────────────────────────

    @Test
    @DisplayName("one 30-day payment of 15 000 covering today → MRR 15 000, paying 1")
    void oneMonthPayment() {
        var m = new RevenueMetrics(List.of(
                pay(A, 15_000, "2026-10-01T09:00:00Z", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 30))), List.of());

        assertThat(m.mrrAt(TODAY)).isEqualTo(15_000);
        assertThat(m.mrrAt(TODAY) * 12).isEqualTo(180_000);
        assertThat(m.payingAt(TODAY)).isEqualTo(1);
    }

    @Test
    @DisplayName("45 000 over 91 days → monthly value 14 835 (rounded to the XAF)")
    void threeMonthPayment_rounding() {
        var p = pay(A, 45_000, "2026-10-01T09:00:00Z", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 30));
        assertThat(java.time.temporal.ChronoUnit.DAYS.between(p.periodStart(), p.periodEnd()) + 1).isEqualTo(91);

        assertThat(RevenueMetrics.monthlyValue(p)).isEqualTo(14_835);
    }

    @Test
    @DisplayName("rounding is half-up: 10 XAF over 60 days → 5")
    void rounding_halfUp() {
        assertThat(RevenueMetrics.monthlyValue(
                pay(A, 10, "2026-10-01T09:00:00Z", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 29)))).isEqualTo(5);
        assertThat(RevenueMetrics.monthlyValue(
                pay(A, 1, "2026-10-01T09:00:00Z", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 29)))).isEqualTo(1); // 0.5 → 1
    }

    @Test
    @DisplayName("two payments covering today (early renewal) → only the latest paid_at counted")
    void twoPaymentsCoveringToday_latestOnly() {
        var m = new RevenueMetrics(List.of(
                pay(A, 15_000, "2026-09-20T09:00:00Z", LocalDate.of(2026, 9, 20), LocalDate.of(2026, 10, 19)),
                pay(A, 30_000, "2026-10-10T09:00:00Z", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 30))), List.of());

        assertThat(m.mrrAt(TODAY)).isEqualTo(30_000);
        assertThat(m.payingAt(TODAY)).isEqualTo(1);
    }

    @Test
    @DisplayName("payment whose period ended yesterday is not in MRR")
    void expiredPayment_notCounted() {
        var m = new RevenueMetrics(List.of(
                pay(A, 15_000, "2026-09-13T09:00:00Z", LocalDate.of(2026, 9, 13), TODAY.minusDays(1))), List.of());

        assertThat(m.mrrAt(TODAY)).isZero();
        assertThat(m.payingAt(TODAY)).isZero();
        assertThat(m.mrrAt(TODAY.minusDays(1))).isEqualTo(15_000);
    }

    @Test
    @DisplayName("manual downgrade before period_end → out of MRR from the downgrade date, back after a new PREMIUM event")
    void downgradeStopsPayment() {
        var p = pay(A, 15_000, "2026-10-01T09:00:00Z", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 30));
        var m = new RevenueMetrics(List.of(p), List.of(
                event(A, "PREMIUM_TRIAL", "PREMIUM", "ADMIN_ACTIVATION", "2026-10-01T09:00:00Z"),
                event(A, "PREMIUM", "FREE", "ADMIN_DOWNGRADE", "2026-10-10T15:00:00Z")));

        assertThat(m.mrrAt(LocalDate.of(2026, 10, 9))).isEqualTo(15_000);
        assertThat(m.mrrAt(LocalDate.of(2026, 10, 10))).isZero();
        assertThat(m.payingAt(TODAY)).isZero();

        var back = new RevenueMetrics(List.of(p), List.of(
                event(A, "PREMIUM", "FREE", "ADMIN_DOWNGRADE", "2026-10-10T15:00:00Z"),
                event(A, "FREE", "PREMIUM", "ADMIN_ACTIVATION", "2026-10-12T10:00:00Z")));
        assertThat(back.mrrAt(LocalDate.of(2026, 10, 11))).isZero();
        assertThat(back.mrrAt(TODAY)).isEqualTo(15_000);
    }

    // ── New paying / collected ────────────────────────────────────────────────

    @Test
    @DisplayName("new paying = first-ever payment in range (WAT date); renewals are not new")
    void newPaying_firstPaymentOnly() {
        var m = new RevenueMetrics(List.of(
                pay(A, 15_000, "2026-09-05T09:00:00Z", LocalDate.of(2026, 9, 5), LocalDate.of(2026, 10, 4)),
                pay(A, 15_000, "2026-10-04T09:00:00Z", LocalDate.of(2026, 10, 5), LocalDate.of(2026, 11, 3)),
                // 30/09 23:30 UTC = 01/10 00:30 WAT → October
                pay(B, 10_000, "2026-09-30T23:30:00Z", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 30))), List.of());

        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 31);
        assertThat(m.newPayingTenants(from, to)).containsExactly(B);
        assertThat(m.revenueCollected(from, to)).isEqualTo(25_000);
    }

    // ── Churn ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PREMIUM→FREE EXPIRY on 2026-09-12 with 4 paying at 2026-09-01 → 1 churned, 25 %")
    void churn_rate() {
        LocalDate s = LocalDate.of(2026, 8, 15);
        var m = new RevenueMetrics(List.of(
                pay(A, 15_000, "2026-08-15T09:00:00Z", s, LocalDate.of(2026, 9, 11)),
                pay(B, 15_000, "2026-08-15T09:00:00Z", s, LocalDate.of(2026, 9, 30)),
                pay(C, 15_000, "2026-08-15T09:00:00Z", s, LocalDate.of(2026, 9, 30)),
                pay(D, 15_000, "2026-08-15T09:00:00Z", s, LocalDate.of(2026, 9, 30))),
                List.of(event(A, "PREMIUM", "FREE", "EXPIRY", "2026-09-12T01:00:00Z")));

        LocalDate first = LocalDate.of(2026, 9, 1);
        LocalDate last = LocalDate.of(2026, 9, 30);
        var churned = m.churned(first, last);
        assertThat(churned).containsOnlyKeys(A);
        assertThat(m.churnBase(first, last)).isEqualTo(4);
        assertThat(RevenueMetrics.rate(churned.size(), m.churnBase(first, last))).isEqualTo(0.25);
    }

    @Test
    @DisplayName("period_end = last day of the previous month, EXPIRY on the 1st → churned and in the base (≤ 100 %)")
    void churn_periodEndLastDayOfPreviousMonth() {
        var m = new RevenueMetrics(List.of(
                pay(A, 15_000, "2026-08-01T09:00:00Z", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))),
                List.of(event(A, "PREMIUM", "FREE", "EXPIRY", "2026-09-01T01:00:00Z")));

        LocalDate first = LocalDate.of(2026, 9, 1);
        LocalDate last = LocalDate.of(2026, 9, 30);
        assertThat(m.churned(first, last)).containsOnlyKeys(A);
        assertThat(m.payingAt(first)).isZero();
        assertThat(m.churnBase(first, last)).isEqualTo(1);
        assertThat(RevenueMetrics.rate(1, m.churnBase(first, last))).isEqualTo(1.0);
    }

    @Test
    @DisplayName("first paid mid-range and churned in range → in the base")
    void churn_newPayerChurnedInRange() {
        var m = new RevenueMetrics(List.of(
                pay(A, 15_000, "2026-09-05T09:00:00Z", LocalDate.of(2026, 9, 5), LocalDate.of(2026, 10, 4))),
                List.of(event(A, "PREMIUM", "FREE", "ADMIN_DOWNGRADE", "2026-09-20T10:00:00Z")));

        assertThat(m.churnBase(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).isEqualTo(1);
    }

    @Test
    @DisplayName("plan end date: EXPIRY → event date − 1; ADMIN_DOWNGRADE → event date")
    void planEndDate() {
        assertThat(RevenueMetrics.planEndDate(event(A, "PREMIUM", "FREE", "EXPIRY", "2026-09-12T01:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(RevenueMetrics.planEndDate(event(A, "PREMIUM", "FREE", "ADMIN_DOWNGRADE", "2026-09-12T10:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 9, 12));
    }

    @Test
    @DisplayName("FREE then PREMIUM again in the same month → not churned; admin downgrade counts")
    void churn_returnSameMonth() {
        var m = new RevenueMetrics(List.of(), List.of(
                event(A, "PREMIUM", "FREE", "EXPIRY", "2026-09-12T01:00:00Z"),
                event(A, "FREE", "PREMIUM", "ADMIN_ACTIVATION", "2026-09-20T10:00:00Z"),
                event(B, "PREMIUM", "FREE", "ADMIN_DOWNGRADE", "2026-09-15T10:00:00Z"),
                event(C, "PREMIUM_TRIAL", "FREE", "EXPIRY", "2026-09-15T10:00:00Z"))); // trial expiry: not churn

        assertThat(m.churned(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))).containsOnlyKeys(B);
    }

    @Test
    @DisplayName("churn, return, churn again in the month → churned at the last churn")
    void churn_twiceInMonth() {
        var m = new RevenueMetrics(List.of(), List.of(
                event(A, "PREMIUM", "FREE", "EXPIRY", "2026-09-02T01:00:00Z"),
                event(A, "FREE", "PREMIUM", "ADMIN_ACTIVATION", "2026-09-05T10:00:00Z"),
                event(A, "PREMIUM", "FREE", "ADMIN_DOWNGRADE", "2026-09-25T10:00:00Z")));

        assertThat(m.churned(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).get(A).occurredAt())
                .isEqualTo(Instant.parse("2026-09-25T10:00:00Z"));
    }

    @Test
    @DisplayName("rate with a zero denominator is 0")
    void rate_zeroDenominator() {
        assertThat(RevenueMetrics.rate(3, 0)).isZero();
    }

    // ── Conversion ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("conversion: trial activated in range converted; trial expired in range counted as ended")
    void conversion() {
        var m = new RevenueMetrics(List.of(), List.of(
                event(A, null, "PREMIUM_TRIAL", "REGISTRATION", "2026-04-01T10:00:00Z"),
                event(A, "PREMIUM_TRIAL", "PREMIUM", "ADMIN_ACTIVATION", "2026-10-05T10:00:00Z"),
                event(B, null, "PREMIUM_TRIAL", "REGISTRATION", "2026-04-02T10:00:00Z"),
                event(B, "PREMIUM_TRIAL", "FREE", "EXPIRY", "2026-10-02T01:00:00Z"),
                // trial ended before the range, activated in the range: neither ended nor converted here
                event(C, null, "PREMIUM_TRIAL", "REGISTRATION", "2026-01-01T10:00:00Z"),
                event(C, "PREMIUM_TRIAL", "FREE", "EXPIRY", "2026-07-01T01:00:00Z"),
                event(C, "FREE", "PREMIUM", "ADMIN_ACTIVATION", "2026-10-07T10:00:00Z"),
                // registered on FREE (no trial): never counted
                event(D, null, "FREE", "REGISTRATION", "2026-04-03T10:00:00Z"),
                event(D, "FREE", "PREMIUM", "ADMIN_ACTIVATION", "2026-10-06T10:00:00Z")));

        var c = m.conversion(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThat(c.converted()).isEqualTo(1);
        assertThat(c.trialsEnded()).isEqualTo(2);
        assertThat(c.rate()).isEqualTo(0.5);
        assertThat(c.converted()).isLessThanOrEqualTo(c.trialsEnded());
    }

    @Test
    @DisplayName("no data → zeros everywhere")
    void noData() {
        var m = new RevenueMetrics(List.of(), List.of());
        assertThat(m.mrrAt(TODAY)).isZero();
        assertThat(m.payingAt(TODAY)).isZero();
        assertThat(m.churned(TODAY, TODAY)).isEmpty();
        assertThat(m.conversion(TODAY, TODAY).rate()).isZero();
        assertThat(m.revenueCollected(TODAY, TODAY)).isZero();
    }

    @Test
    @DisplayName("tenure streak: starts at the latest continuous paid streak (gap ≤ 1 day), not the first payment ever")
    void streakStart() {
        var m = new RevenueMetrics(List.of(
                pay(A, 15_000, "2025-01-01T09:00:00Z", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 30)),
                // long gap → new streak
                pay(A, 15_000, "2026-05-01T09:00:00Z", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)),
                pay(A, 15_000, "2026-06-01T09:00:00Z", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)),   // consecutive
                pay(A, 15_000, "2026-07-02T09:00:00Z", LocalDate.of(2026, 7, 2), LocalDate.of(2026, 8, 31))),  // 1 unpaid day
                List.of());

        assertThat(m.streakStart(A, LocalDate.of(2026, 8, 31))).contains(LocalDate.of(2026, 5, 1));
        assertThat(m.streakStart(A, LocalDate.of(2025, 1, 30))).contains(LocalDate.of(2025, 1, 1));
        assertThat(m.streakStart(B, LocalDate.of(2026, 8, 31))).isEmpty();

        var gap2 = new RevenueMetrics(List.of(
                pay(A, 1, "2026-05-01T09:00:00Z", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)),
                pay(A, 1, "2026-06-03T09:00:00Z", LocalDate.of(2026, 6, 3), LocalDate.of(2026, 6, 30))), List.of());
        assertThat(gap2.streakStart(A, LocalDate.of(2026, 6, 30))).contains(LocalDate.of(2026, 6, 3));
    }

    @Test
    @DisplayName("tenure: months between two dates with one decimal")
    void monthsBetween() {
        assertThat(RevenueMetrics.monthsBetween(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 12))).isEqualTo(8.3);
        assertThat(RevenueMetrics.monthsBetween(LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 12))).isZero();
    }
}
