package com.keevo.inventory.api;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Story 22.19 (D11) — an offline sale synchronised beyond the store's stock is applied (the goods are gone) but the
 * level never stays negative: an automatic {@code ADJUSTMENT} movement « {@value #NOTE} » brings it back to 0. Its id
 * is derived from the sale movement's id ({@link #adjustmentIdOf}), so the sync layer can tell which pushed sale lines
 * were floored and warn the device.
 */
public final class StockFloor {

    /** Note of the automatic adjustment movement. */
    public static final String NOTE = "Écart constaté à la synchronisation";

    private StockFloor() {
    }

    /** The id of the adjustment that floored the level after sale movement {@code saleMovementId}. */
    public static UUID adjustmentIdOf(UUID saleMovementId) {
        return UUID.nameUUIDFromBytes(("stock-floor:" + saleMovementId).getBytes(StandardCharsets.UTF_8));
    }
}
