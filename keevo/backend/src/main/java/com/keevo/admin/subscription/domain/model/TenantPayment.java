package com.keevo.admin.subscription.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * TenantPayment — one off-platform payment recorded by the super admin (Story 9-2), stored in
 * {@code public.tenant_payments}. Append-only: a payment entered by mistake is voided, never
 * edited or deleted; 9-3 derives MRR from the non-voided ones.
 *
 * @param periodStart first day covered (WAT)
 * @param periodEnd   last day covered (WAT), the Premium expiry date
 * @param voidedAt    when the payment was voided, null when it counts
 * @param voidReason  reason of the void, null when it counts
 */
public record TenantPayment(
        UUID id,
        UUID tenantId,
        long amountXaf,
        PaymentMethod method,
        Instant paidAt,
        LocalDate periodStart,
        LocalDate periodEnd,
        String note,
        UUID recordedBy,
        Instant voidedAt,
        String voidReason
) {

    /** A new (non-voided) payment. */
    public TenantPayment(UUID id, UUID tenantId, long amountXaf, PaymentMethod method, Instant paidAt,
                         LocalDate periodStart, LocalDate periodEnd, String note, UUID recordedBy) {
        this(id, tenantId, amountXaf, method, paidAt, periodStart, periodEnd, note, recordedBy, null, null);
    }

    public boolean isVoided() {
        return voidedAt != null;
    }
}
