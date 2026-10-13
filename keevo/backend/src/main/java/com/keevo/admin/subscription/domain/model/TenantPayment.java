package com.keevo.admin.subscription.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * TenantPayment — one off-platform payment recorded by the super admin (Story 9-2), stored in
 * {@code public.tenant_payments}. Append-only; 9-3 derives MRR from it.
 *
 * @param periodStart first day covered (WAT)
 * @param periodEnd   last day covered (WAT), the Premium expiry date
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
        UUID recordedBy
) {}
