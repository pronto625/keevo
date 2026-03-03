package com.keevo.identity.auth.domain.port.out;

import com.keevo.identity.auth.domain.model.Tenant;

/**
 * TenantRepository — Driven port for tenant persistence.
 *
 * <p>Interface — implemented in the persistence adapter layer.
 * Targets the PUBLIC schema tenant registry.
 */
public interface TenantRepository {

    /** Persist a new tenant. */
    Tenant save(Tenant tenant);

    /** Check if a tenant code already exists (for uniqueness during provisioning). */
    boolean existsByCode(String code);
}
