package com.keevo.catalog.api;

import java.util.UUID;

/**
 * Story 17.12 — checks a stock-tracking change against the stock (implemented by {@code inventory}, optional: the
 * catalogue starts without it). Declared here so the catalogue keeps no dependency on the inventory module.
 */
public interface StockTrackingGuard {

    /**
     * Refuses turning tracking off while the product has an in-transit transfer (it could never be received).
     *
     * @throws com.keevo.shared.domain.exception.DomainException {@code TRANSFER_IN_TRANSIT}
     */
    void checkCanDisable(String tenantSchema, UUID productId);
}
