package com.keevo.shared.event.catalog.contact;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * ClientMarketingConsentChangedEvent — Story 20.2: the team recorded ({@code consent = true}) or withdrew
 * ({@code false}) a client's consent to receive marketing messages. Audited (« Accord marketing donné / retiré »).
 */
public record ClientMarketingConsentChangedEvent(
        UUID clientId,
        String name,
        boolean consent,
        UUID storeId,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
