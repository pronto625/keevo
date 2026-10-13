package com.keevo.admin.subscription.domain.port.in;

import java.util.UUID;

/** SuspendTenantCommand — read-only lockout of a tenant, reason required (Story 9-2). */
public record SuspendTenantCommand(UUID actorId, UUID tenantId, String reason) {}
