package com.keevo.admin.sync_monitoring.domain.model;

import java.time.Instant;

public record AdminTenantSyncHealth(
        String tenantId,
        String tenantName,
        String tenantPlan,
        long failedOps7d,
        long conflicts7d,
        int deviceCount,
        Instant lastPushAt,
        Instant lastPullAt,
        String status
) {}
