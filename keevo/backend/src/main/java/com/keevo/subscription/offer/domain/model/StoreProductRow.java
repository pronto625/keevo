package com.keevo.subscription.offer.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Story 21.2 — an App Store product of the catalogue ({@code public.store_products}) joined with its cycle and the grid
 * price of its plan × cycle.
 *
 * @param declaredPriceXaf Apple price entered by the super-admin, {@code null} when not entered
 * @param observedPrice    price of the last purchase seen in a store event (in {@code observedCurrency})
 * @param observedPriceXaf its XAF equivalent when known
 * @param gridPriceXaf     {@code public.plan_prices} amount of the plan × cycle, {@code null} when none
 * @param cycleArchived    the billing cycle is archived (the product is no longer offered to new buyers)
 */
public record StoreProductRow(String store, String productId, String planCode, String cycleCode, String cycleLabel,
                              int months, boolean active, Long declaredPriceXaf, BigDecimal observedPrice,
                              String observedCurrency, Long observedPriceXaf, Instant observedAt, Long gridPriceXaf,
                              Instant updatedAt, boolean cycleArchived) {
}
