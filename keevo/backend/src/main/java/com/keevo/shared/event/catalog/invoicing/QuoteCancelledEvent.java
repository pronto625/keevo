package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 20.3 — a quote was cancelled (it never had a number). Audited by {@code AuditEventListener}; amounts in XAF
 * francs (TTC).
 */
public record QuoteCancelledEvent(
        UUID quoteId,
        String reference, String clientName, long totalTtc, String reason,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
