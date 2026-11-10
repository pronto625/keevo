package com.keevo.payments.checkout.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static com.keevo.shared.domain.time.KeevoZones.WAT;

/**
 * Story 21.1 — the period a confirmed payment covers: a renewal (the same plan, of kind PAID, still running) is extended
 * from the day after its last day (already-paid days are never lost, ADR-06); otherwise — another plan, an expired
 * plan, a trial — the period starts today and the plan switches at once. It lasts the cycle's months; the plan expires at 23:59:59 (WAT) of its last day. Pure.
 *
 * @param start first covered day (WAT)
 * @param end   last covered day (WAT)
 */
public record PlanPeriod(LocalDate start, LocalDate end, Instant expiresAt) {

    private static final LocalTime LAST_SECOND = LocalTime.of(23, 59, 59);

    /**
     * @param currentPaid   the payment renews the company's current plan, of kind PAID
     * @param currentExpiry its expiry, {@code null} when none
     */
    public static PlanPeriod of(boolean currentPaid, Instant currentExpiry, Instant now, int months) {
        if (months <= 0) {
            throw new IllegalArgumentException("months must be positive");
        }
        LocalDate today = LocalDate.ofInstant(now, WAT);
        LocalDate start = today;
        if (currentPaid && currentExpiry != null) {
            LocalDate currentEnd = LocalDate.ofInstant(currentExpiry, WAT);
            if (!currentEnd.isBefore(today)) {
                start = currentEnd.plusDays(1);
            }
        }
        LocalDate end = start.plusMonths(months).minusDays(1);
        return new PlanPeriod(start, end, expiryOf(end));
    }

    /**
     * The plan expiry of a period ending on {@code lastDay}: 23:59:59 (WAT) of that day. The one conversion of a
     * covered period into a plan expiry (21.1 confirmation, 21.2 return to a running Mobile Money period).
     */
    public static Instant expiryOf(LocalDate lastDay) {
        return lastDay.atTime(LAST_SECOND).atZone(WAT).toInstant();
    }
}
