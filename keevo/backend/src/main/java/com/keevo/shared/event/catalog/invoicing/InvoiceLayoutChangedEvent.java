package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 20.7 — the printing layout of a document changed. Audited by {@code AuditEventListener}. {@code templateLabel}
 * = French name of the layout (« Ticket classique »…, « Modèle du compte » when cleared); {@code ticketWidth} =
 * « 58 mm » / « 80 mm » / « A4 », {@code null} = the account's.
 */
public record InvoiceLayoutChangedEvent(
        UUID invoiceId,
        String reference, boolean quote, String templateLabel, String ticketWidth,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
