package com.keevo.shared.module.application.port;

import com.keevo.shared.module.TenantModuleStatus;

import java.util.Map;
import java.util.Optional;

/**
 * Read port of the per-tenant module state ({@code public.tenant_modules}, spec-socle §2.2), used by the
 * {@code ModuleRegistry}. Implemented by {@code shared.module.adapter.out.persistence.JdbcTenantModuleStateStore}.
 */
public interface TenantModuleStatePort {

    /**
     * Every {@code tenant_modules} row of the tenant (module id → status), in one query that resolves the tenant
     * from its schema name. Rows of modules that are not installed are included (the caller filters).
     *
     * @param tenantSchema a valid tenant schema name ({@code kv_xxxxxx})
     * @return empty when no non-{@code DELETED} {@code public.tenants} row has this schema name; otherwise the rows (possibly none)
     */
    Optional<Map<String, TenantModuleStatus>> findStatuses(String tenantSchema);
}
