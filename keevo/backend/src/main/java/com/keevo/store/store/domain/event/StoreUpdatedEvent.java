package com.keevo.store.store.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * StoreUpdatedEvent — Domain event published after a store is updated.
 *
 * <p>GoF Pattern: Observer — published via Spring's ApplicationEventPublisher,
 * consumed by AuditEventListener.
 *
 * <p>Story 3.1 — AC4.
 */
public record StoreUpdatedEvent(
        UUID storeId,
        String newName,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
