package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 14.1 — a draft invoice was created. Published by the invoicing module, audited by
 * {@code AuditEventListener}; amounts in XAF francs (TTC).
 */
public record InvoiceCreatedEvent(
        UUID invoiceId,
        String clientName, long totalTtc,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
