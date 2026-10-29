package com.keevo.identity.api;

import java.util.List;
import java.util.Optional;

/**
 * Read access to the platform tenants ({@code public.tenants}) for other modules (Story 12.2d).
 * Not tenant-scoped: it reads the public schema whatever the current tenant context.
 */
public interface TenantQueryPort {

    /** Every tenant, whatever its status (callers filter on {@link TenantView#status()}). */
    List<TenantView> findAll();

    Optional<TenantView> findBySchemaName(String schemaName);
}
