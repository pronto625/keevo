package com.keevo.admin.notification.domain.port.in;

import com.keevo.admin.notification.domain.model.PushTenantRef;

import java.util.List;

/** Story 9-5 — companies a global push can target, matched by name (picker « Un commerce »). */
public interface SearchPushTenantsUseCase {
    int LIMIT = 20;

    List<PushTenantRef> searchTenants(String search);
}
