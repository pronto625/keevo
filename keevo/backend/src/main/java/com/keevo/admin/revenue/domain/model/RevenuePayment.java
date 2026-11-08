package com.keevo.admin.revenue.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One off-platform payment of {@code public.tenant_payments} (Story 9-2), as read by the revenue
 * dashboard (Story 9-3).
 *
 * @param periodStart first day covered (inclusive)
 * @param periodEnd   last day covered (inclusive)
 * @param planCode    Story 21.10 — plan paid for, {@code null} when unknown (legacy row)
 * @param cycleCode   Story 21.10 — billing cycle paid for, {@code null} when free-form
 */
public record RevenuePayment(UUID tenantId, long amountXaf, Instant paidAt, LocalDate periodStart, LocalDate periodEnd,
                             String planCode, String cycleCode) {

    /** Plan and cycle unknown. */
    public RevenuePayment(UUID tenantId, long amountXaf, Instant paidAt, LocalDate periodStart, LocalDate periodEnd) {
        this(tenantId, amountXaf, paidAt, periodStart, periodEnd, null, null);
    }
}
