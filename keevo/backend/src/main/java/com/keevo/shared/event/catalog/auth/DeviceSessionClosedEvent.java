package com.keevo.shared.event.catalog.auth;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 16.11 — one or more device sessions were closed (« Appareils connectés »), or a replayed
 * refresh token revoked a device chain. Audited as « Appareil déconnecté » / « Toutes les autres
 * sessions fermées » / « Session coupée (jeton réutilisé) ».
 *
 * @param actorId      who closed it ({@code null} when the server closed it: replay)
 * @param tenantId     account schema
 * @param targetUserId user whose session(s) were closed
 * @param scope        {@link Scope}
 * @param deviceLabel  readable device label (single session), may be null
 * @param sessions     number of sessions closed
 * @param occurredAt   when
 */
public record DeviceSessionClosedEvent(
        UUID actorId,
        String tenantId,
        UUID targetUserId,
        Scope scope,
        String deviceLabel,
        int sessions,
        Instant occurredAt
) implements DomainEvent {

    public enum Scope {
        /** One session closed by its user or the owner. */
        ONE,
        /** Every other session of the user closed. */
        ALL_OTHERS,
        /** A replayed refresh token revoked the device chain. */
        REPLAY
    }
}
