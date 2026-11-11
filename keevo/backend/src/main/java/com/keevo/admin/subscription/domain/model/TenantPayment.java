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
 * @param planCode    Story 21.10 — the plan the payment paid for, null for a legacy row the backfill could not tie
 * @param cycleCode   Story 21.10 — the billing cycle it paid for, null when free-form (« Durée libre »)
 * @param online      Story 21.1 — written by a confirmed online payment of the owner (module {@code payments}), not by
 *                    the super admin
 * @param creditXaf   Story 24.5 — the part of the price paid with the owner's account credit ({@code amountXaf} is the
 *                    cash collected; both count in the MRR, only the cash in the revenue collected)
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
        String voidReason,
        String planCode,
        String cycleCode,
        boolean online,
        long creditXaf
) {

    /** Story 21.1 shape: no credit part. */
    public TenantPayment(UUID id, UUID tenantId, long amountXaf, PaymentMethod method, Instant paidAt,
                         LocalDate periodStart, LocalDate periodEnd, String note, UUID recordedBy, Instant voidedAt,
                         String voidReason, String planCode, String cycleCode, boolean online) {
        this(id, tenantId, amountXaf, method, paidAt, periodStart, periodEnd, note, recordedBy, voidedAt, voidReason,
                planCode, cycleCode, online, 0);
    }

    /** A payment recorded by the super admin (Story 21.10 shape). */
    public TenantPayment(UUID id, UUID tenantId, long amountXaf, PaymentMethod method, Instant paidAt,
                         LocalDate periodStart, LocalDate periodEnd, String note, UUID recordedBy, Instant voidedAt,
                         String voidReason, String planCode, String cycleCode) {
        this(id, tenantId, amountXaf, method, paidAt, periodStart, periodEnd, note, recordedBy, voidedAt, voidReason,
                planCode, cycleCode, false);
    }

    /** A new (non-voided) payment, plan and cycle unknown. */
    public TenantPayment(UUID id, UUID tenantId, long amountXaf, PaymentMethod method, Instant paidAt,
                         LocalDate periodStart, LocalDate periodEnd, String note, UUID recordedBy) {
        this(id, tenantId, amountXaf, method, paidAt, periodStart, periodEnd, note, recordedBy, null, null);
    }

    /** Story 21.10 — a new (non-voided) payment for a plan and an optional cycle. */
    public static TenantPayment forPlan(UUID id, UUID tenantId, long amountXaf, PaymentMethod method, Instant paidAt,
                                        LocalDate periodStart, LocalDate periodEnd, String note, UUID recordedBy,
                                        String planCode, String cycleCode) {
        return new TenantPayment(id, tenantId, amountXaf, method, paidAt, periodStart, periodEnd, note, recordedBy,
                null, null, planCode, cycleCode);
    }

    /** A payment as stored before Story 21.10 (plan and cycle unknown). */
    public TenantPayment(UUID id, UUID tenantId, long amountXaf, PaymentMethod method, Instant paidAt,
                         LocalDate periodStart, LocalDate periodEnd, String note, UUID recordedBy, Instant voidedAt,
                         String voidReason) {
        this(id, tenantId, amountXaf, method, paidAt, periodStart, periodEnd, note, recordedBy, voidedAt, voidReason,
                null, null);
    }

    public boolean isVoided() {
        return voidedAt != null;
    }
}
