package com.keevo.inventory.counting.domain.model;

/**
 * InventoryScope — Defines the scope of an inventory session.
 *
 * <p>FULL = count all active products in the store.
 * PARTIAL = count only products in specific categories.
 */
@org.springframework.modulith.NamedInterface("events") // 12.2f: carried by InventorySessionCreatedEvent, merged into "inventory :: events"
public enum InventoryScope {
    FULL,
    PARTIAL
}
