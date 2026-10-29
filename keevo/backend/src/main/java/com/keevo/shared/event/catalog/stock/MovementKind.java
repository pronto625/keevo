package com.keevo.shared.event.catalog.stock;

/**
 * MovementKind — stock movement type carried by {@link StockAdjustedEvent} (Story 12.3).
 *
 * <p>Same constants, same names as inventory's internal {@code MovementType}; the producer maps by name,
 * so audit details ({@code movementType}) are unchanged.
 */
public enum MovementKind {
    SALE,
    STOCK_ENTRY,
    TRANSFER_IN,
    TRANSFER_OUT,
    ADJUSTMENT,
    SALE_CANCELLED
}
