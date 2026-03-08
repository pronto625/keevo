package com.keevo.catalog.product.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * ProductArchivedEvent — Domain event published after successful product archiving.
 *
 * <p>GoF Pattern: Observer — published via Spring's ApplicationEventPublisher,
 * consumed by {@link com.keevo.shared.infrastructure.web.AuditEventListener}.
 *
 * <p>Soft delete operation — product is marked as archived but data retained.
 * 
 * <p>Story 2.1 — Product CRUD with audit trail integration.
 */
public record ProductArchivedEvent(
        UUID productId,
        String productName,
        String productSku,
        String tenantId,
        UUID actorId,
        Instant occurredAt
) {}