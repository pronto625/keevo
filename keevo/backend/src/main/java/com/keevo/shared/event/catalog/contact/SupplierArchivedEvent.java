package com.keevo.shared.event.catalog.contact;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * SupplierArchivedEvent — domain event published after a supplier is archived.
 * Story 2.5 — Gestion Clients & Fournisseurs.
 */
public record SupplierArchivedEvent(
        UUID supplierId,
        String name,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
