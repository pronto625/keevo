package com.keevo.catalog.api;

/**
 * Result of {@link ProductCommandPort#setStockTracking} (Story 17.12).
 *
 * @param changed {@code true} only for the call that actually switched the setting (conditional update): the
 *                starting quantity and the audit entry are gated on it
 */
public record StockTrackingUpdate(ProductView product, boolean changed) {}
