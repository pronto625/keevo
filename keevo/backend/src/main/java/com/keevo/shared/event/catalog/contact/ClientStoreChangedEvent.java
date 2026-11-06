package com.keevo.shared.event.catalog.contact;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * ClientStoreChangedEvent — Story 20.2 (ADR-08): the owner moved a client to another store (its data becomes visible
 * to that store's team). Audited « Client changé de boutique » with the store names, never their ids.
 *
 * @param fromStoreName {@code null} for a client without store (visible from every store)
 */
public record ClientStoreChangedEvent(
        UUID clientId,
        String name,
        String fromStoreName,
        String toStoreName,
        UUID toStoreId,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
