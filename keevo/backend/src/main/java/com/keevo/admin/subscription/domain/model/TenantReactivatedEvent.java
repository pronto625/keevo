package com.keevo.admin.subscription.domain.model;

import java.time.Instant;
import java.util.UUID;

/** TenantReactivatedEvent — super admin lifted a tenant suspension, Story 9-2. */
public record TenantReactivatedEvent(UUID actorId, UUID tenantId, String schemaName, Instant occurredAt) {}
