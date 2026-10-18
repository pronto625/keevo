package com.keevo.admin.operations.domain.model;

import java.time.Instant;

/**
 * Activity of one tenant over a period (Story 9.7).
 *
 * @param plan           plan code (FREE | PREMIUM_TRIAL | PREMIUM), labelled by the dashboard
 * @param lastActivityAt latest merchant event ({@link com.keevo.admin.TenantLastActivitySql}), null when never active
 */
public record TenantActivity(
        String tenantId,
        String tenantName,
        String plan,
        long gmv,
        long orderCount,
        Instant lastActivityAt,
        int storeCount
) {}
