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
 */
public record RevenuePayment(UUID tenantId, long amountXaf, Instant paidAt, LocalDate periodStart, LocalDate periodEnd) {}
