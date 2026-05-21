package com.keevo.admin.sync_monitoring.domain.port.in;

import com.keevo.admin.sync_monitoring.domain.model.AdminSyncTenantDetail;

public interface GetSyncTenantDetailUseCase {
    AdminSyncTenantDetail execute(GetSyncTenantDetailQuery query);
}
