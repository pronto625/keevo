package com.keevo.shared.event.catalog.store;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * StoreUpdatedEvent — Domain event published after a store is updated.
 *
 * <p>GoF Pattern: Observer — published via Spring's ApplicationEventPublisher,
 * consumed by AuditEventListener.
 *
 * <p>Story 3.1 — AC4. Story 19.12 — {@code before} / {@code after}: the fields that changed (name, address, phone,
 * Bref fields with French values), or for a logo change only {@code logo} « Ajouté » / « Remplacé » / « Supprimé »
 * in {@code after}; both empty when nothing changed.
 */
public record StoreUpdatedEvent(
        UUID storeId,
        String newName,
        UUID actorId,
        String tenantId,
        Instant occurredAt,
        Map<String, Object> before,
        Map<String, Object> after
) implements DomainEvent {

    public StoreUpdatedEvent {
        before = before == null ? Map.of() : before;
        after = after == null ? Map.of() : after;
    }

    /** Pre-19.12 arity — no other field changed. */
    public StoreUpdatedEvent(UUID storeId, String newName, UUID actorId, String tenantId, Instant occurredAt) {
        this(storeId, newName, actorId, tenantId, occurredAt, Map.of(), Map.of());
    }
}
