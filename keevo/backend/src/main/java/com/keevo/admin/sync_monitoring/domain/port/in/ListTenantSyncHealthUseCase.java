package com.keevo.admin.sync_monitoring.domain.port.in;

import com.keevo.admin.sync_monitoring.domain.model.AdminTenantSyncHealth;

import java.util.List;

public interface ListTenantSyncHealthUseCase {
    List<AdminTenantSyncHealth> listTenantSyncHealth();
}
