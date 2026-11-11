package com.keevo.inventory.counting.domain.port.in;

import java.util.UUID;

/**
 * CancelInventorySessionCommand — Pure Java command for cancelling an inventory session.
 *
 * <p>Story 19.5b — the actor's rights are decided by the inbound adapter and carried as plain values (no role here):
 *
 * @param sessionId        the session to cancel
 * @param actorId          the actor
 * @param anyStarter       {@code true} when the actor may cancel a session started by someone else (owner, or a
 *                         holder of {@code INVENTORY_SESSION_MANAGE}); {@code false} = his own sessions only
 * @param restrictedStore  the only store whose sessions the actor may cancel (a store member's store), {@code null}
 *                         for every store (owner)
 */
public record CancelInventorySessionCommand(
        UUID sessionId,
        UUID actorId,
        boolean anyStarter,
        UUID restrictedStore
) {

    /** An owner-level cancellation: any session of any store. */
    public static CancelInventorySessionCommand unrestricted(UUID sessionId, UUID actorId) {
        return new CancelInventorySessionCommand(sessionId, actorId, true, null);
    }
}
