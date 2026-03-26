package com.keevo.inventory.counting.domain.port.in;

import java.util.UUID;

/**
 * ListInventorySessionsQuery — Query for listing past inventory sessions.
 */
public record ListInventorySessionsQuery(
        UUID storeId,  // nullable — null means all stores
        int page,
        int size
) {}
