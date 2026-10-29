package com.keevo.shared.module.application.port;

import java.util.List;

/**
 * Write side of {@code public.tenant_modules} used by the module provisioner (Story 13.4), plus the list of live
 * tenants for batch provisioning. Implemented by the 13.2 state store
 * ({@code shared.module.adapter.out.persistence.JdbcTenantModuleStateStore}).
 */
public interface TenantModuleProvisioningPort {

    /**
     * Sets {@code provisioned_version} on the existing (tenant, module) row; status untouched. Monotonic: a version
     * lower than the stored one (numeric comparison per dot-separated part, {@code 2.10 > 2.9}) is not written, so a
     * concurrent or older node never regresses it.
     *
     * @param version a non-null Flyway version ({@code 1}, {@code 2.1}…)
     * @return the number of rows updated (0 when no row, no live tenant, or a higher version is already stored)
     */
    int updateProvisionedVersion(String tenantSchema, String moduleId, String version);

    /**
     * Inserts an {@code ACTIVE}/{@code FREE} row ({@code activated_at = now()}) with {@code provisioned_version}
     * for a live tenant; if a concurrent writer created the row first, only its {@code provisioned_version} is set
     * (monotonic, as {@link #updateProvisionedVersion}).
     *
     * @param version a non-null Flyway version
     * @return the number of rows written (0 when no live tenant has this schema, or a higher version is stored)
     */
    int insertActiveFree(String tenantSchema, String moduleId, String version);

    /** Every live tenant ({@code status <> 'DELETED'}), ordered by code. */
    List<LiveTenant> liveTenants();

    /**
     * @param code         public tenant code ({@code KV-XXXXXX}), the only identity used in logs and reports
     * @param tenantSchema  schema name (not validated here)
     * @param schemaPresent whether the schema exists in the database ({@code pg_namespace})
     */
    record LiveTenant(String code, String tenantSchema, boolean schemaPresent) {
    }
}
