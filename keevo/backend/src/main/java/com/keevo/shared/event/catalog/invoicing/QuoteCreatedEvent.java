package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 20.3 — a quote (« devis ») was created. {@code reference} = its display reference ({@code DV-XXXXXXXX}).
 * Published by the invoicing module, audited by {@code AuditEventListener}; amounts in XAF francs (TTC).
 */
public record QuoteCreatedEvent(
        UUID quoteId,
        String reference, String clientName, long totalTtc,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
