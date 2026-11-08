package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 20.7 — the public link of a document was regenerated: every link shared before stops opening. Audited by
 * {@code AuditEventListener}; never carries the link itself.
 */
public record InvoiceLinkRotatedEvent(
        UUID invoiceId,
        String reference, boolean quote,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
