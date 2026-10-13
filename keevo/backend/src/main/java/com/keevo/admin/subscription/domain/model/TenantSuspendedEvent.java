package com.keevo.admin.subscription.domain.model;

import java.time.Instant;
import java.util.UUID;

/** TenantSuspendedEvent — super admin suspended a tenant (read-only lockout), Story 9-2. */
public record TenantSuspendedEvent(UUID actorId, UUID tenantId, String reason, Instant occurredAt) {}
