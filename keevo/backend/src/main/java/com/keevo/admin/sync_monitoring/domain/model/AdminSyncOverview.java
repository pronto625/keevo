package com.keevo.admin.sync_monitoring.domain.model;

import java.time.Instant;

public record AdminSyncOverview(
        long totalDevices,
        long totalFailedOps7d,
        long totalConflicts7d,
        int tenantsInAlert,
        Instant latestPushGlobally
) {}
