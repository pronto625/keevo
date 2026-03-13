package com.keevo.store.store.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * StoreDeactivatedEvent — Domain event published after a store is deactivated.
 *
 * <p>GoF Pattern: Observer — published via Spring's ApplicationEventPublisher,
 * consumed by AuditEventListener.
 *
 * <p>Story 3.1 — AC5.
 */
public record StoreDeactivatedEvent(
        UUID storeId,
        String name,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
