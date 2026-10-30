package com.keevo.shared.module.application.port;

import java.util.Map;
import java.util.Optional;

/**
 * Short-lived cache of a tenant's module rows (spec-socle §5.1: cached per request). Implemented by
 * {@code shared.module.infrastructure.RequestScopedActiveModulesCache}: the current HTTP request's attributes when
 * a request is bound, nothing otherwise (no stale state outside a request).
 *
 * <p>Since Story 13.5 the cache holds the raw rows, not a computed state: the effective state depends on the time
 * ({@code expires_at}, {@code grace_until}) and is recomputed from the rows at every call.
 */
public interface ActiveModulesCache {

    /**
     * What is cached for a tenant: whether it is a known (registered, not deleted) tenant — Story 12.7 tells an
     * unknown tenant from a tenant whose modules are off — and its {@code tenant_modules} rows (empty for an
     * unknown tenant).
     */
    record Entry(boolean knownTenant, Map<String, TenantModuleStatePort.Row> rows, TenantModuleStatePort.Plan plan) {

        public Entry {
            rows = Map.copyOf(rows);
            plan = plan != null ? plan : TenantModuleStatePort.Plan.NONE;
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
