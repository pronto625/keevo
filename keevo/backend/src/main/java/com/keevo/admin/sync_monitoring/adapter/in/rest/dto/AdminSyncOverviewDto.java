package com.keevo.admin.sync_monitoring.adapter.in.rest.dto;

import com.keevo.admin.sync_monitoring.domain.model.AdminSyncOverview;

import java.time.Instant;

public record AdminSyncOverviewDto(
        long totalDevices,
        long totalFailedOps7d,
        long totalConflicts7d,
        int tenantsInAlert,
        Instant latestPushGlobally
) {
    public static AdminSyncOverviewDto from(AdminSyncOverview model) {
        return new AdminSyncOverviewDto(
                model.totalDevices(),
                model.totalFailedOps7d(),
                model.totalConflicts7d(),
                model.tenantsInAlert(),
                model.latestPushGlobally());
    }
}
