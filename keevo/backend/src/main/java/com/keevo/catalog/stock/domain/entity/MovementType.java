package com.keevo.catalog.stock.domain.entity;

/**
 * MovementType — all permitted stock operation types.
 *
 * <p>SALE: stock consumed by a POS sale (Epic 4).
 * <p>STOCK_ENTRY: manual stock replenishment (receipt of goods).
 * <p>TRANSFER_IN: stock received from another store (Epic 3).
 * <p>TRANSFER_OUT: stock sent to another store (Epic 3).
 * <p>ADJUSTMENT: manual correction — shrinkage, breakage, inventory reconciliation.
 */
public enum MovementType {
    SALE,
    STOCK_ENTRY,
    TRANSFER_IN,
    TRANSFER_OUT,
    ADJUSTMENT
}
