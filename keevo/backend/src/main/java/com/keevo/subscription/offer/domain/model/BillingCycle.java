package com.keevo.subscription.offer.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Story 21.10 — a billing duration of the offer ({@code public.billing_cycles}): offer content, never a constant of
 * the code. Archived cycles keep their prices and history but are no longer offered; codes are never deleted.
 *
 * @param code       the cycle code ({@code MONTHLY}…)
 * @param label      French name (« Mensuel »)
 * @param months     length in months, 1..36
 * @param sortOrder  display order (ascending)
 * @param archivedAt archive instant, {@code null} when live
 */
public record BillingCycle(String code, String label, int months, int sortOrder, Instant archivedAt) {

    public BillingCycle {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(label, "label");
    }

    public boolean archived() {
        return archivedAt != null;
    }

    public BillingCycle withArchivedAt(Instant at) {
        return new BillingCycle(code, label, months, sortOrder, at);
    }
}
