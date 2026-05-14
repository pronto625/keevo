package com.keevo.admin.tenant.domain.port.in;

import com.keevo.admin.tenant.domain.model.AdminTenantDetail;

/**
 * GetTenantDetailUseCase — port for loading the full tenant detail panel data.
 */
public interface GetTenantDetailUseCase {
    AdminTenantDetail execute(GetTenantDetailQuery query);
}
