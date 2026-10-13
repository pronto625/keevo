package com.keevo.admin.subscription.adapter.in.rest.dto;

import com.keevo.admin.subscription.domain.model.PlanAlert;

import java.time.Instant;
import java.util.UUID;

/**
 * PlanAlertDto — entry of {@code GET /api/v1/admin/subscriptions/alerts} (Story 9-2).
 * {@code tenantId} only serves to open the tenant drawer; the dashboard displays {@code tenantName}.
 */
public record PlanAlertDto(
        UUID tenantId,
        String tenantName,
        String kind,
        String plan,
        Integer daysLeft,
        Instant expiresAt,
        Instant occurredAt
) {
    public static PlanAlertDto from(PlanAlert a) {
        return new PlanAlertDto(a.tenantId(), a.tenantName(), a.kind().name(), a.plan(),
                a.daysLeft(), a.expiresAt(), a.occurredAt());
    }
}
