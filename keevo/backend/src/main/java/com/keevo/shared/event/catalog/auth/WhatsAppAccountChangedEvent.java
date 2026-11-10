package com.keevo.shared.event.catalog.auth;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 21.5 — an owner connected or disconnected the company's WhatsApp Business number (module whatsapp-cloud).
 * Audited as « WhatsApp Business connecté » / « WhatsApp Business déconnecté » with the number as Meta shows it
 * (never the access token).
 *
 * @param actorId            the owner
 * @param tenantId           account schema
 * @param accountId          the company id (one account per company)
 * @param displayPhoneNumber the number as Meta displays it ({@code null} when Meta gave none)
 * @param change             {@link Change}
 * @param occurredAt         when
 */
public record WhatsAppAccountChangedEvent(
        UUID actorId,
        String tenantId,
        UUID accountId,
        String displayPhoneNumber,
        Change change,
        Instant occurredAt
) implements DomainEvent {

    public enum Change { CONNECTED, DISCONNECTED }
}
