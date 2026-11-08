package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 20.7 — a client or seller signature was added to ({@code removed=false}) or removed from a document. Published
 * by the invoicing module, audited by {@code AuditEventListener}. {@code reference} = the number ({@code FAC-…}), the
 * quote reference ({@code DV-…}) or « Brouillon »; {@code roleLabel} = « Signature client » / « Signature vendeur ».
 */
public record InvoiceSignatureChangedEvent(
        UUID invoiceId,
        String reference, boolean quote, String roleLabel, boolean removed,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
