package com.keevo.inventory.counting.domain.port.in;

import java.util.UUID;

/**
 * Command encapsulating the intent to validate an inventory session.
 * GoF Command pattern — pure data object, no behavior.
 * Story 6.4.
 */
public record ValidateInventoryCommand(UUID sessionId, UUID actorId) {}
