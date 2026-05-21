package com.keevo.admin.sync_monitoring.adapter.in.rest.dto;

import com.keevo.admin.sync_monitoring.domain.model.AdminTenantSyncHealth;

import java.time.Instant;

public record AdminTenantSyncHealthDto(
        String tenantId,
        String tenantName,
        String tenantPlan,
        long failedOps7d,
        long conflicts7d,
        int deviceCount,
        Instant lastPushAt,
        Instant lastPullAt,
        String status
) {
    public static AdminTenantSyncHealthDto from(AdminTenantSyncHealth model) {
        return new AdminTenantSyncHealthDto(
                model.tenantId(),
                model.tenantName(),
                model.tenantPlan(),
                model.failedOps7d(),
                model.conflicts7d(),
                model.deviceCount(),
                model.lastPushAt(),
                model.lastPullAt(),
                model.status());
    }
}
