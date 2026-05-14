package com.keevo.admin.tenant.domain.port.in;

import java.util.UUID;

/**
 * GetTenantDetailQuery — query parameters for the tenant detail drawer.
 *
 * @param tenantId  UUID of the target tenant
 * @param auditPage 0-based page number for audit log (50 events/page)
 */
public record GetTenantDetailQuery(
        UUID tenantId,
        int auditPage
) {}
