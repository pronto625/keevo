package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 14.2 — an issued (or paid) invoice was sent to the client with a link to its PDF. Published by the invoicing
 * module ({@code invoice.sent}), audited by {@code AuditEventListener}. {@code maskedPhone} = the masked recipient
 * only (« +237 6•• ••• 111 », Story 20.8: « c***@ndolo.cm » for an email): the full recipient never leaves the
 * module. Amount in XAF francs (TTC).
 * Story 20.7: {@code quote} = a quote was sent ({@code number} = its {@code DV-…} reference; audited « Devis envoyé »);
 * an imported invoice without Keevo number carries its {@code BRF-…} reference.
 * Story 20.8: {@code channel} = {@code WHATSAPP}, {@code SMS} or {@code EMAIL}; {@code null} (an event stored before
 * 20.8, read back by the publication registry) = WhatsApp.
 */
public record InvoiceSentEvent(
        UUID invoiceId,
        String number, String clientName, long totalTtc, String maskedPhone,
        UUID actorId,
        String tenantId,
        Instant occurredAt,
        boolean quote,
        String channel
) implements DomainEvent {

    /** WhatsApp, the only channel before Story 20.8. */
    public static final String WHATSAPP = "WHATSAPP";

    /** Story 14.2 shape: an invoice sent by WhatsApp. */
    public InvoiceSentEvent(UUID invoiceId, String number, String clientName, long totalTtc, String maskedPhone,
                            UUID actorId, String tenantId, Instant occurredAt) {
        this(invoiceId, number, clientName, totalTtc, maskedPhone, actorId, tenantId, occurredAt, false, WHATSAPP);
    }

    /** Story 20.7 shape: an invoice or a quote sent by WhatsApp. */
    public InvoiceSentEvent(UUID invoiceId, String number, String clientName, long totalTtc, String maskedPhone,
                            UUID actorId, String tenantId, Instant occurredAt, boolean quote) {
        this(invoiceId, number, clientName, totalTtc, maskedPhone, actorId, tenantId, occurredAt, quote, WHATSAPP);
    }

    /** The channel, {@link #WHATSAPP} for an event stored before Story 20.8. */
    public String channelOrDefault() {
        return channel == null || channel.isBlank() ? WHATSAPP : channel;
    }
}
