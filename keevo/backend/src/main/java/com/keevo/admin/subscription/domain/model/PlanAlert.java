package com.keevo.admin.subscription.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * PlanAlert — a paid/trial plan expiring within 7 days, or downgraded by expiry in the last 7 days
 * (Story 9-2, FR81).
 *
 * @param tenantId   tenant UUID (to open the drawer, never displayed)
 * @param tenantName tenant business name
 * @param kind       EXPIRING or EXPIRED
 * @param plan       plan code concerned (PREMIUM / PREMIUM_TRIAL), labelled by the dashboard
 * @param daysLeft   WAT calendar days before expiry (EXPIRING only, else null)
 * @param expiresAt  expiry instant (EXPIRING only)
 * @param occurredAt downgrade time (EXPIRED only)
 */
public record PlanAlert(
        UUID tenantId,
        String tenantName,
        Kind kind,
        String plan,
        Integer daysLeft,
        Instant expiresAt,
        Instant occurredAt
) {
    public enum Kind { EXPIRING, EXPIRED }
}
