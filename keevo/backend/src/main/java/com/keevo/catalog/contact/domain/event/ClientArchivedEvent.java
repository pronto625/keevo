package com.keevo.catalog.contact.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * ClientArchivedEvent — domain event published after a client is archived.
 * Story 2.5 — Gestion Clients & Fournisseurs.
 */
public record ClientArchivedEvent(
        UUID clientId,
        String name,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
