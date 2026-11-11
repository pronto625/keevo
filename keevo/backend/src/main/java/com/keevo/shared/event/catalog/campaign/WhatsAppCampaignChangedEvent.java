package com.keevo.shared.event.catalog.campaign;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 21.6 — an owner created, changed, launched, scheduled, cancelled or deleted a WhatsApp campaign of the company
 * (module whatsapp-cloud), or its send ended. Audited with the campaign name (never an id on screen).
 *
 * @param actorId    the owner ({@code null} for the end of a send, a system fact)
 * @param tenantId   account schema
 * @param campaignId the campaign
 * @param name       its name
 * @param change     {@link Change}
 * @param detail     a short French detail (recipients count…), or {@code null}
 * @param occurredAt when
 */
public record WhatsAppCampaignChangedEvent(
        UUID actorId,
        String tenantId,
        UUID campaignId,
        String name,
        Change change,
        String detail,
        Instant occurredAt
) implements DomainEvent {

    public enum Change { CREATED, UPDATED, DELETED, SEND_STARTED, SCHEDULED, CANCELLED, COMPLETED }
}
