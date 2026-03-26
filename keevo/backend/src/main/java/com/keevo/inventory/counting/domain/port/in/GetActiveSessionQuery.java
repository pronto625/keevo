package com.keevo.inventory.counting.domain.port.in;

import java.util.UUID;

/**
 * GetActiveSessionQuery — Query for retrieving the active session for a store.
 */
public record GetActiveSessionQuery(UUID storeId) {}
