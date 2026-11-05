package com.keevo.shared.module.application.port;

import com.keevo.shared.capability.entitlement.PlanProfile;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Short-lived cache of a tenant's module rows (spec-socle §5.1: cached per request). Implemented by
 * {@code shared.module.infrastructure.RequestScopedActiveModulesCache}: the current HTTP request's attributes when
 * a request is bound, else the open {@link com.keevo.shared.module.ModuleStateScope} (one tenant's work in a
 * scheduler or listener), nothing otherwise (no stale state).
 *
 * <p>Since Story 13.5 the cache holds the raw rows, not a computed state: the effective state depends on the time
 * ({@code expires_at}, {@code grace_until}) and is recomputed from the rows at every call.
 */
public interface ActiveModulesCache {

    /**
     * What is cached for a tenant: whether it is a known (registered, not deleted) tenant — Story 12.7 tells an
     * unknown tenant from a tenant whose modules are off — and its {@code tenant_modules} rows (empty for an
     * unknown tenant). Story 16.8: {@code profile} is the profile of the tenant's plan type, read with the rows when a
     * paid module has no row ({@code null}: not needed, or built-in defaults). Story 19.13: {@code ownerDisabled} =
     * the modules the owner disabled ({@code public.tenant_module_activation}, read in the same lookup).
     */
    record Entry(boolean knownTenant, Map<String, TenantModuleStatePort.Row> rows, TenantModuleStatePort.Plan plan,
                 PlanProfile profile, Set<String> ownerDisabled) {

        public Entry {
            rows = Map.copyOf(rows);
            plan = plan != null ? plan : TenantModuleStatePort.Plan.NONE;
            ownerDisabled = ownerDisabled == null ? Set.of() : Set.copyOf(ownerDisabled);
        }

        /** No owner-disabled module. */
        public Entry(boolean knownTenant, Map<String, TenantModuleStatePort.Row> rows,
                     TenantModuleStatePort.Plan plan, PlanProfile profile) {
            this(knownTenant, rows, plan, profile, Set.of());
        }

        /**
         * Without a plan profile: paid modules without a row follow the built-in defaults.
         */
        public Entry(boolean knownTenant, Map<String, TenantModuleStatePort.Row> rows,
                     TenantModuleStatePort.Plan plan) {
            this(knownTenant, rows, plan, null);
        }

        public Entry(boolean knownTenant, Map<String, TenantModuleStatePort.Row> rows) {
            this(knownTenant, rows, TenantModuleStatePort.Plan.NONE);
        }
    }

    /** The cached entry of the tenant, if any. */
    Optional<Entry> get(String tenantSchema);

    /** Caches the entry of the tenant (no-op when there is nothing to cache into). */
    void put(String tenantSchema, Entry entry);

    /** Drops the tenant's cached entry (no-op when nothing is cached or nothing can be). */
    void evict(String tenantSchema);
}
