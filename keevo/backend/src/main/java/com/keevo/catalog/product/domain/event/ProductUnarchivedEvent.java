package com.keevo.catalog.product.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * ProductUnarchivedEvent — Domain event published after successful product unarchiving.
 *
 * <p>Mirrors {@link ProductArchivedEvent} for the reverse operation.
 *
 * <p>Story 5.1 — Push Sync handler fix.
 */
public record ProductUnarchivedEvent(
        UUID productId,
        String productName,
        String productSku,
        String tenantId,
        UUID actorId,
        Instant occurredAt
) {}
