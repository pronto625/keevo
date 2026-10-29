package com.keevo.catalog.category.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * CategoryActiveChangedEvent — domain event published after a category's active flag
 * actually changed (toggle or delete = deactivate) (v1s-14-15).
 *
 * @param active the new state: {@code true} = activated, {@code false} = deactivated
 */
@org.springframework.modulith.NamedInterface("events") // 12.2a: merged into "catalog :: events" (see package-info)
public record CategoryActiveChangedEvent(
        UUID categoryId,
        String name,
        boolean active,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
