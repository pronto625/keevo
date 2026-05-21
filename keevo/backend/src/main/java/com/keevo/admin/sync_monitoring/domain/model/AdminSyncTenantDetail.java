package com.keevo.admin.sync_monitoring.domain.model;

import java.util.List;

public record AdminSyncTenantDetail(
        String tenantId,
        String tenantName,
        String tenantPlan,
        List<AdminDeviceInfo> devices,
        List<AdminSyncFailedOp> recentFailedOps,
        List<AdminSyncConflict> recentConflicts
) {}
