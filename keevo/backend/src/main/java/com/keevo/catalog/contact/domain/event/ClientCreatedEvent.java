package com.keevo.catalog.contact.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * ClientCreatedEvent — domain event published after a client is successfully created.
 *
 * <p>GoF Pattern: Observer — published via Spring's ApplicationEventPublisher,
 * consumed by {@link com.keevo.shared.infrastructure.web.AuditEventListener}.
 *
 * <p>Story 2.5 — Gestion Clients & Fournisseurs.
 */
public record ClientCreatedEvent(
        UUID clientId,
        String name,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
