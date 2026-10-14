package com.keevo.store.store.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * StoreReactivatedEvent — Domain event published after a deactivated store is reactivated.
 *
 * <p>GoF Pattern: Observer — consumed by AuditEventListener (STORE_REACTIVATED).
 */
public record StoreReactivatedEvent(
        UUID storeId,
        String name,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
