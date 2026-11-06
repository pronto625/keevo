package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Story 20.4 — a collection row changed on an invoice: a payment received ({@link Kind#RECEIVED}, a deposit is the
 * first one), an instalment scheduled ({@link Kind#SCHEDULED}, {@code dueDate}) or settled ({@link Kind#SETTLED},
 * {@code paidAt}). Published by the invoicing module, audited by {@code AuditEventListener}; amounts in XAF francs.
 * {@code number} is {@code null} for a draft (an instalment scheduled before the issue).
 */
public record InvoicePaymentRecordedEvent(
        UUID invoiceId,
        String number, String clientName,
        Kind kind, long amount, LocalDate dueDate, Instant paidAt, String method,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {

    /** What happened to the row. */
    public enum Kind { RECEIVED, SCHEDULED, SETTLED }
}
