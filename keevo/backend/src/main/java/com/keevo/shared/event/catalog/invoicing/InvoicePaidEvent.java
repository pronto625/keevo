package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 14.1 — a issued invoice was marked paid. Published by the invoicing module, audited by
 * {@code AuditEventListener}; amounts in XAF francs (TTC).
 */
public record InvoicePaidEvent(
        UUID invoiceId,
        String number, String clientName, long totalTtc, String paymentMethod,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
