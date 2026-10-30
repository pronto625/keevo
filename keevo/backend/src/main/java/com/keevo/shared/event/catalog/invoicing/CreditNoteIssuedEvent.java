package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 14.7 — a credit note (« avoir ») was issued on an issued or paid invoice, manually or automatically (sale
 * cancelled / corrected). Published by the invoicing module, audited by {@code AuditEventListener}; amount in XAF
 * francs (TTC, positive — the credited amount).
 */
public record CreditNoteIssuedEvent(
        UUID creditNoteId,
        String number,
        UUID invoiceId,
        String invoiceNumber,
        long totalTtc,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
