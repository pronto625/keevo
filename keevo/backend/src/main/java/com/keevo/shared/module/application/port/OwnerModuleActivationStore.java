package com.keevo.shared.module.application.port;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Story 19.13 — persistence of the owner's module activation ({@code public.tenant_module_activation}). Implemented
 * by {@code shared.module.adapter.out.persistence.JdbcOwnerModuleActivationStore}. Never touches
 * {@code tenant_modules}.
 */
public interface OwnerModuleActivationStore {

    /** The {@code public.tenants.id} of the live (non-{@code DELETED}) tenant with this schema. */
    Optional<UUID> findTenantId(String tenantSchema);

    /** Every owner-activation row of the tenant: module id → activated (one PK-prefix read). */
    Map<String, Boolean> findActivations(UUID tenantId);

    /** Inserts or updates the tenant's choice for the module (one upsert). */
    void upsert(UUID tenantId, String moduleId, boolean activated, UUID actorId, Instant at);
}
