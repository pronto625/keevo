package com.keevo.shared.event.catalog.contact;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * SupplierUnarchivedEvent — domain event published after an archived supplier is restored
 * (sync UNARCHIVE_SUPPLIER).
 */
public record SupplierUnarchivedEvent(
        UUID supplierId,
        String name,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
