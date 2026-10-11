package com.keevo.inventory.counting.domain.port.in;

import com.keevo.inventory.counting.domain.model.InventoryScope;

import java.util.List;
import java.util.UUID;

/**
 * CreateInventorySessionCommand — Pure Java command for creating an inventory session.
 *
 * <p>{@code sessionId} is nullable: REST callers let the domain generate the id; the sync
 * handler passes the client-generated id so a replay is an idempotent no-op (v1s-13-8).
 */
public record CreateInventorySessionCommand(
        UUID sessionId,
        UUID storeId,
        InventoryScope scope,
        List<UUID> categoryIds,
        UUID actorId
) {
    public CreateInventorySessionCommand(UUID storeId, InventoryScope scope,
                                         List<UUID> categoryIds, UUID actorId) {
        this(null, storeId, scope, categoryIds, actorId);
    }
}
