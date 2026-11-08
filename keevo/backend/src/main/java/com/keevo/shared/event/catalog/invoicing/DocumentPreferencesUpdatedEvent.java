package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 20.7 — the account's document defaults changed (layout, ticket paper, public link lifetime). Audited by
 * {@code AuditEventListener} on the invoicing settings entity. Values already in French ({@code templateLabel}
 * « Modèle Keevo » when none, {@code ticketWidth} « 80 mm »…).
 */
public record DocumentPreferencesUpdatedEvent(
        String templateLabel, String ticketWidth, int publicLinkDays,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
