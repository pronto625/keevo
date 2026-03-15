package com.keevo.catalog.stock.domain.model;

import com.keevo.store.store.domain.model.StoreType;

import java.time.Instant;
import java.util.UUID;

/**
 * CrossStoreAvailabilityEntry — single store entry in a product's cross-store availability view.
 *
 * <p>Holds per-store stock data for the cross-store availability query (AC5).
 * {@code refreshedAt} carries the query timestamp — same value for all entries in one response.
 *
 * Story 3.4. GoF: part of Query Object pattern result.
 */
public record CrossStoreAvailabilityEntry(
        UUID      storeId,
        String    storeName,
        StoreType storeType,
        int       quantity,
        int       minimumThreshold,
        boolean   isLow,
        Instant   refreshedAt
) {
    public CrossStoreAvailabilityEntry {
        if (minimumThreshold < 0) throw new IllegalArgumentException("threshold must be >= 0");
    }
}
