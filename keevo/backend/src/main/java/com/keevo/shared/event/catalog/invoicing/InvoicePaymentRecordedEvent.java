package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Story 20.4 — a collection row changed on an invoice: a payment received ({@link Kind#RECEIVED}, a deposit is the
 * first one), an instalment scheduled ({@link Kind#SCHEDULED}, {@code dueDate}) or settled ({@link Kind#SETTLED},
 * {@code paidAt}); Story 22.19 (D8) — an unpaid instalment changed ({@link Kind#UPDATED}: its new amount and due
 * date, with the previous ones in {@code previousAmount} / {@code previousDueDate}). Published by the invoicing module, audited by {@code AuditEventListener}; amounts in XAF francs.
 * {@code number} is {@code null} for a draft (an instalment scheduled before the issue).
 */
public record InvoicePaymentRecordedEvent(
        UUID invoiceId,
        String number, String clientName,
        Kind kind, long amount, LocalDate dueDate, Instant paidAt, String method,
        UUID actorId,
        String tenantId,
        Instant occurredAt,
        Long previousAmount,
        LocalDate previousDueDate
) implements DomainEvent {

    /** Every kind but {@link Kind#UPDATED}: no previous values. */
    public InvoicePaymentRecordedEvent(UUID invoiceId, String number, String clientName, Kind kind, long amount,
                                       LocalDate dueDate, Instant paidAt, String method, UUID actorId, String tenantId,
                                       Instant occurredAt) {
        this(invoiceId, number, clientName, kind, amount, dueDate, paidAt, method, actorId, tenantId, occurredAt,
                null, null);
    }

    /** What happened to the row. */
    public enum Kind { RECEIVED, SCHEDULED, SETTLED, UPDATED }
}
