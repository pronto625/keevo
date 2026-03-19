package com.keevo.identity.auth.domain.port.out;

import com.keevo.identity.auth.domain.model.Tenant;

import java.util.Optional;
import java.util.UUID;

/**
 * TenantRepository — Driven port for tenant persistence.
 *
 * <p>Interface — implemented in the persistence adapter layer.
 * Targets the PUBLIC schema tenant registry.
 */
public interface TenantRepository {

    /** Persist a new tenant. */
    Tenant save(Tenant tenant);

    /** Find a tenant by its primary key. */
    Optional<Tenant> findById(UUID id);

    /**
     * Find a tenant by its schema name (e.g., {@code "kv_abc123"}).
     *
     * <p>Required because {@code LoginResponse.tenantId} exposes the schemaName, not the UUID.
     * Flutter uses that value as a tenant identifier in all admin API calls.
     */
    Optional<Tenant> findBySchemaName(String schemaName);

    /** Check if a tenant code already exists (for uniqueness during provisioning). */
    boolean existsByCode(String code);

    /**
     * Story 1.7 — Find a tenant by its public code (e.g., "KV-ABC123").
     * Used by SelectTenantService to resolve a tenantCode → Tenant before issuing the scoped JWT.
     */
    Optional<Tenant> findByCode(String tenantCode);

    /**
     * Story 4.4 — Find all tenants for auto-closure scheduling.
     * Returns all tenants regardless of status (scheduler filters by ACTIVE).
     */
    java.util.List<Tenant> findAll();
}
