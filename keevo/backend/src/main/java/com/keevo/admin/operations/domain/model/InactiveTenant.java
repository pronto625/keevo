package com.keevo.admin.operations.domain.model;

import java.time.Instant;

/**
 * A dormant tenant: no sale and no audit_log entry for more than the inactivity threshold (Story 9.7).
 *
 * @param lastActivityAt         null when the tenant was never active
 * @param daysSinceLastActivity  whole days since {@code lastActivityAt}, null when never active
 */
public record InactiveTenant(
        String tenantId,
        String tenantName,
        String plan,
        Instant lastActivityAt,
        Long daysSinceLastActivity
) {}
