package com.keevo.shared.event.catalog.category;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * CategoryRenamedEvent — domain event published after a category name actually changed (v1s-14-15).
 */
public record CategoryRenamedEvent(
        UUID categoryId,
        String oldName,
        String newName,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
