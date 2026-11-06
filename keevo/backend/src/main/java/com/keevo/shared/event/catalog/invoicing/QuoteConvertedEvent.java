package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 20.3 — a quote was converted into the invoice {@code invoiceNumber} (issued in the same transaction, which
 * also publishes its own {@link InvoiceIssuedEvent}). Audited by {@code AuditEventListener}; amounts in XAF (TTC).
 */
public record QuoteConvertedEvent(
        UUID quoteId,
        String reference,
        UUID invoiceId, String invoiceNumber,
        String clientName, long totalTtc,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
