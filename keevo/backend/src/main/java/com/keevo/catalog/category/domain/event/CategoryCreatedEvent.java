package com.keevo.catalog.category.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * CategoryCreatedEvent — domain event published after a category is created (v1s-14-15).
 *
 * @param parentId null for a root category
 */
public record CategoryCreatedEvent(
        UUID categoryId,
        String name,
        UUID parentId,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
