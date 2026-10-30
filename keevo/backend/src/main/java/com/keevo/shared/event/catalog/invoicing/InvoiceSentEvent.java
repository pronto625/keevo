package com.keevo.shared.event.catalog.invoicing;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 14.2 — an issued (or paid) invoice was sent to the client by WhatsApp with a link to its PDF. Published by
 * the invoicing module ({@code invoice.sent}), audited by {@code AuditEventListener}. {@code maskedPhone} only
 * (« +237 6•• ••• 111 »): the full number never leaves the module. Amount in XAF francs (TTC).
 */
public record InvoiceSentEvent(
        UUID invoiceId,
        String number, String clientName, long totalTtc, String maskedPhone,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
