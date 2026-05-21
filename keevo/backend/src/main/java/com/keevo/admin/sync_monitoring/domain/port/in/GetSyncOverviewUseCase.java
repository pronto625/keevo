package com.keevo.admin.sync_monitoring.domain.port.in;

import com.keevo.admin.sync_monitoring.domain.model.AdminSyncOverview;

public interface GetSyncOverviewUseCase {
    AdminSyncOverview getSyncOverview();
}
