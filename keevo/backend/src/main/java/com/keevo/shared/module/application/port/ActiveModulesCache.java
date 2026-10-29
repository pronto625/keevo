package com.keevo.shared.module.application.port;

import java.util.Optional;
import java.util.Set;

/**
 * Short-lived cache of a tenant's effective active modules (spec-socle §5.1: cached per request). Implemented by
 * {@code shared.module.infrastructure.RequestScopedActiveModulesCache}: the current HTTP request's attributes when
 * a request is bound, nothing otherwise (no stale state outside a request).
 */
public interface ActiveModulesCache {

    /** The cached active modules of the tenant, if any. */
    Optional<Set<String>> get(String tenantSchema);

    /** Caches the (immutable) active modules of the tenant (no-op when there is nothing to cache into). */
    void put(String tenantSchema, Set<String> activeModules);

    /** Drops the tenant's cached active modules (no-op when nothing is cached or nothing can be). */
    void evict(String tenantSchema);
}
