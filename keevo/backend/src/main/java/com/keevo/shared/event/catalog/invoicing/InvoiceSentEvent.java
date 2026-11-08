package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 14.2 — an issued (or paid) invoice was sent to the client by WhatsApp with a link to its PDF. Published by
 * the invoicing module ({@code invoice.sent}), audited by {@code AuditEventListener}. {@code maskedPhone} only
 * (« +237 6•• ••• 111 »): the full number never leaves the module. Amount in XAF francs (TTC).
 * Story 20.7: {@code quote} = a quote was sent ({@code number} = its {@code DV-…} reference; audited « Devis envoyé »);
 * an imported invoice without Keevo number carries its {@code BRF-…} reference.
 */
public record InvoiceSentEvent(
        UUID invoiceId,
        String number, String clientName, long totalTtc, String maskedPhone,
        UUID actorId,
        String tenantId,
        Instant occurredAt,
        boolean quote
) implements DomainEvent {

    /** Story 14.2 shape: an invoice. */
    public InvoiceSentEvent(UUID invoiceId, String number, String clientName, long totalTtc, String maskedPhone,
                            UUID actorId, String tenantId, Instant occurredAt) {
        this(invoiceId, number, clientName, totalTtc, maskedPhone, actorId, tenantId, occurredAt, false);
    }
}
