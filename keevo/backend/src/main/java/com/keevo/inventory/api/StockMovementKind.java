package com.keevo.inventory.api;

/**
 * Kinds of stock movement another module may request (Story 12.2b). Transfer legs are not
 * offered: they belong to the inventory's own transfer flow. Each constant maps by name onto the
 * internal movement type, so stored values are unchanged.
 */
public enum StockMovementKind {
    /** Stock consumed by a sale. */
    SALE,
    /** Stock restored by cancelling a completed sale. */
    SALE_CANCELLED,
    /** Goods received (initial stock, replenishment). */
    STOCK_ENTRY,
    /** Manual correction (e.g. sale correction). */
    ADJUSTMENT
}
