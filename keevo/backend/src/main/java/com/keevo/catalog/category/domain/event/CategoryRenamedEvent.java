package com.keevo.catalog.category.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * CategoryRenamedEvent — domain event published after a category name actually changed (v1s-14-15).
 */
@org.springframework.modulith.NamedInterface("events") // 12.2a: merged into "catalog :: events" (see package-info)
public record CategoryRenamedEvent(
        UUID categoryId,
        String oldName,
        String newName,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
