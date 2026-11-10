package com.keevo.payments.checkout.domain.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Story 21.2 (correctif post-test appareil) — the plan period of the company's latest confirmed Mobile Money plan
 * payment ({@code public.payment_requests}, CONFIRMED, kind PLAN): the plan bought and the last covered day (WAT). A
 * revoked App Store subscription gives the company back to this period while it still runs. Pure.
 *
 * @param planCode the plan the payment bought
 * @param end      last covered day (WAT), as stored by the 21.1 confirmation
 */
public record PaidPlanPeriod(String planCode, LocalDate end) {

    public PaidPlanPeriod {
        if (planCode == null || planCode.isBlank() || end == null) {
            throw new IllegalArgumentException("plan and last day are required");
        }
    }

    /** The plan expiry of this period: 23:59:59 (WAT) of its last day, exactly as 21.1 set it. */
    public Instant expiresAt() {
        return PlanPeriod.expiryOf(end);
    }

    /** Whether the period still covers {@code today} (WAT date): its last day is today or later. */
    public boolean runningOn(LocalDate today) {
        return !end.isBefore(today);
    }
}
