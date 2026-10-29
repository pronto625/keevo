package com.keevo.shared.event.catalog.counting;

/**
 * InventoryScopeKind — inventory session scope carried by {@link InventorySessionCreatedEvent} (Story 12.3).
 *
 * <p>Same constants, same names as inventory's internal {@code InventoryScope}; the producer maps by name,
 * so audit details ({@code scope}) are unchanged.
 */
public enum InventoryScopeKind {
    FULL,
    PARTIAL
}
