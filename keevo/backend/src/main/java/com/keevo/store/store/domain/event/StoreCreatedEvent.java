package com.keevo.store.store.domain.event;

import com.keevo.store.store.domain.model.StoreType;

import java.time.Instant;
import java.util.UUID;

/**
 * StoreCreatedEvent — Domain event published after successful store creation.
 *
 * <p>GoF Pattern: Observer — published via Spring's ApplicationEventPublisher,
 * consumed by AuditEventListener.
 *
 * <p>Story 3.1 — AC1.
 */
public record StoreCreatedEvent(
        UUID storeId,
        String name,
        StoreType type,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
