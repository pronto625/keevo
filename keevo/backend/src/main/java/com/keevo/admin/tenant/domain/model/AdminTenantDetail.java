package com.keevo.admin.tenant.domain.model;

import java.util.List;

/**
 * AdminTenantDetail — full tenant profile for the detail drawer.
 *
 * <p>Loaded by cross-schema queries: stores + employees from the tenant's own schema,
 * audit log from the tenant's schema, summary from the public schema.
 */
public record AdminTenantDetail(
        AdminTenantListItem summary,
        List<AdminStoreInfo> stores,
        List<AdminEmployeeInfo> employees,
        List<AdminAuditEvent> auditEvents,
        long auditTotalCount
) {

    /** Convenience factory for tests — empty detail with only the summary row. */
    public static AdminTenantDetail empty(AdminTenantListItem summary) {
        return new AdminTenantDetail(summary, List.of(), List.of(), List.of(), 0L);
    }
}
