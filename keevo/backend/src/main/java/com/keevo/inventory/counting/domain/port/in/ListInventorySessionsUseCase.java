package com.keevo.inventory.counting.domain.port.in;

import com.keevo.inventory.counting.domain.model.InventorySession;

import java.util.List;

/**
 * ListInventorySessionsUseCase — Port in for listing inventory sessions with pagination.
 */
public interface ListInventorySessionsUseCase {
    List<InventorySession> execute(ListInventorySessionsQuery query);
}
