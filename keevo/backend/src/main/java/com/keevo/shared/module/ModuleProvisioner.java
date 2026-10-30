package com.keevo.shared.module;

/**
 * Creates and evolves a module's tables inside a tenant schema (Story 13.4, spec-socle §2.3, MOD-FR11).
 *
 * <p>Each module owns versioned Flyway migrations under {@code classpath:db/modules/<moduleId>/}; they run inside the
 * tenant schema ({@code kv_xxxxxx}) with an isolated history table {@code flyway_history_<moduleId>} ({@code -} →
 * {@code _}). Provisioning is idempotent: a second run with nothing pending applies nothing. After a successful
 * migration {@code public.tenant_modules.provisioned_version} is recorded (the highest applied version):
 * <ul>
 *   <li>a row exists for (tenant, module) → only {@code provisioned_version} is updated (status untouched);</li>
 *   <li>no row and the module is active through the free rule (13.2) → a row {@code ACTIVE}/{@code FREE} is
 *       inserted (equivalent to the implicit state);</li>
 *   <li>no row and the module is not active → {@link ModuleNotActiveException}, checked <b>before</b> any migration
 *       (Story 13.8 writes the row first, then provisions).</li>
 * </ul>
 * The write is monotonic (never lowers a stored version); after a partial failure the version actually reached is
 * recorded before the exception is rethrown. A module without Flyway-named scripts is a no-op ({@code migrationsApplied = 0}, no history table, nothing written).
 * {@code core} never gets a {@code tenant_modules} row (it never enters the lifecycle): only an existing row is
 * updated for it.
 */
public interface ModuleProvisioner {

    /**
     * Applies the module's pending migrations to the tenant schema and records the provisioned version.
     *
     * @param tenantSchema a tenant schema name ({@code kv_xxxxxx})
     * @param moduleId     an installed module id
     * @return what was applied
     * @throws IllegalArgumentException    invalid/platform schema, or unknown module (raw values never echoed)
     * @throws TenantNotFoundException    no live tenant for the schema
     * @throws ModuleNotActiveException   the module is not active for the tenant (no row, not free)
     * @throws ModuleProvisioningException a migration or the version write failed ({@code provisioned_version} =
     *                                     the highest version applied before the failure)
     */
    ProvisioningResult provision(String tenantSchema, String moduleId);

    /** Whether the module ships migrations ({@code db/modules/<moduleId>/**}{@code /*.sql} on the classpath). */
    boolean hasMigrations(String moduleId);

    /**
     * Provisions the module for every live tenant ({@code public.tenants.status <> 'DELETED'}), one schema at a time.
     * A tenant whose schema does not exist yet, or for which the module is not active, is skipped; any other failure
     * on one tenant is recorded and the loop
     * continues.
     *
     * @throws IllegalArgumentException unknown module
     */
    ProvisioningReport provisionAll(String moduleId);

    /**
     * Story 13.9 — provisions, for one tenant, every installed module (category {@code platform} excluded) that ships
     * migrations and is active for it, each after its {@code requires} (module id order otherwise). Used at tenant
     * registration, once the core DDL exists. A failure of one module is logged (module id, tenant code, exception
     * class) and the others continue; never throws for a module failure.
     *
     * @param tenantSchema a tenant schema name ({@code kv_xxxxxx})
     * @return the result of each module provisioned (failed or inactive modules are absent)
     * @throws IllegalArgumentException invalid/platform schema
     */
    java.util.List<ProvisioningResult> provisionActiveModules(String tenantSchema);
}
