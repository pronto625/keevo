package com.keevo.catalog.product.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * ProductCreatedEvent — Domain event published after successful product creation.
 *
 * <p>GoF Pattern: Observer — published via Spring's ApplicationEventPublisher,
 * consumed by {@link com.keevo.shared.infrastructure.web.AuditEventListener}.
 *
 * <p>Pure Java record — immutable, no framework dependencies.
 * 
 * <p>Story 2.1 — Product CRUD with audit trail integration.
 */
public record ProductCreatedEvent(
        UUID productId,
        String productName,
        String productSku,
        String tenantId,
        UUID actorId,
        Instant occurredAt
) {}