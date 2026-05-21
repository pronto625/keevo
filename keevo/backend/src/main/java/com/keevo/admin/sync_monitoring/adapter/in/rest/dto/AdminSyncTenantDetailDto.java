package com.keevo.admin.sync_monitoring.adapter.in.rest.dto;

import com.keevo.admin.sync_monitoring.domain.model.AdminSyncTenantDetail;

import java.util.List;

public record AdminSyncTenantDetailDto(
        String tenantId,
        String tenantName,
        String tenantPlan,
        List<AdminDeviceInfoDto> devices,
        List<AdminSyncFailedOpDto> recentFailedOps,
        List<AdminSyncConflictDto> recentConflicts
) {
    public static AdminSyncTenantDetailDto from(AdminSyncTenantDetail model) {
        return new AdminSyncTenantDetailDto(
                model.tenantId(),
                model.tenantName(),
                model.tenantPlan(),
                model.devices().stream().map(AdminDeviceInfoDto::from).toList(),
                model.recentFailedOps().stream().map(AdminSyncFailedOpDto::from).toList(),
                model.recentConflicts().stream().map(AdminSyncConflictDto::from).toList());
    }
}
