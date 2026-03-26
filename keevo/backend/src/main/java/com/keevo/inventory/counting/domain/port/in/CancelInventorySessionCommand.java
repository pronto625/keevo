package com.keevo.inventory.counting.domain.port.in;

import java.util.UUID;

/**
 * CancelInventorySessionCommand — Pure Java command for cancelling an inventory session.
 */
public record CancelInventorySessionCommand(
        UUID sessionId,
        UUID actorId,
        String actorRole
) {}
