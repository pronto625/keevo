package com.keevo.admin.subscription.domain.port.in;

import java.util.UUID;

/** ReactivateTenantUseCase — SUSPENDED → ACTIVE and revoke every member session (Story 9-2). */
public interface ReactivateTenantUseCase {

    void reactivate(UUID actorId, UUID tenantId);
}
