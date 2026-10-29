package com.keevo.shared.module.application.port;

import java.util.Optional;
import java.util.Set;

/**
 * Short-lived cache of a tenant's effective active modules (spec-socle §5.1: cached per request). Implemented by
 * {@code shared.module.infrastructure.RequestScopedActiveModulesCache}: the current HTTP request's attributes when
 * a request is bound, nothing otherwise (no stale state outside a request).
 */
public interface ActiveModulesCache {

    /**
     * What is cached for a tenant: whether it is a known (registered, not deleted) tenant — Story 12.7 tells an
     * unknown tenant from a tenant whose modules are off — and its effective active modules.
     */
    record Entry(boolean knownTenant, Set<String> activeModules) {
    }

    /** The cached entry of the tenant, if any. */
    Optional<Entry> get(String tenantSchema);

    /** Caches the entry of the tenant (no-op when there is nothing to cache into). */
    void put(String tenantSchema, Entry entry);

    /** Drops the tenant's cached active modules (no-op when nothing is cached or nothing can be). */
    void evict(String tenantSchema);
}
