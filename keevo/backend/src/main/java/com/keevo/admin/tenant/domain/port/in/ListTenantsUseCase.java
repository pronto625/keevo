package com.keevo.admin.tenant.domain.port.in;

import com.keevo.admin.tenant.domain.model.AdminTenantListItem;
import org.springframework.data.domain.Page;

/**
 * ListTenantsUseCase — port for the Super Admin paginated tenant list.
 */
public interface ListTenantsUseCase {
    Page<AdminTenantListItem> execute(ListTenantsQuery query);
}
