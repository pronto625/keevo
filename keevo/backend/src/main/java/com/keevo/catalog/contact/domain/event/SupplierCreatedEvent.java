package com.keevo.catalog.contact.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * SupplierCreatedEvent — domain event published after a supplier is successfully created.
 * Story 2.5 — Gestion Clients & Fournisseurs.
 */
public record SupplierCreatedEvent(
        UUID supplierId,
        String name,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
