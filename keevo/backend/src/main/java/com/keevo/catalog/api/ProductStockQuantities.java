package com.keevo.catalog.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Story 16.13 (reprise 10/11/2026) — port <em>required</em> by the catalogue's AI product search and implemented by the
 * module that owns stock (inventory, optional: the catalogue starts without it). Declared here so the catalogue keeps no
 * dependency on the inventory module (same one-way rule as {@link ProductStockPresence}).
 */
public interface ProductStockQuantities {

    /**
     * The real stock ({@code stock_levels}) of each given product: the quantity in {@code onlyStoreId} when set (a store
     * member's store), else the sum over every store (the owner). Products without any stock row are absent (= 0). One
     * grouped query whatever the number of ids.
     */
    Map<UUID, Integer> quantities(String tenantSchema, Collection<UUID> productIds, UUID onlyStoreId);
}
