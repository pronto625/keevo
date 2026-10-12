package com.keevo.shared.application.port.export;

import java.time.Instant;
import java.util.UUID;

/**
 * Export filters (Story v1s-18-1). Every field is optional; {@code from} and {@code to} are
 * inclusive bounds on the dataset's period column.
 *
 * @param storeId one store, or {@code null} for all stores
 * @param from    period start (inclusive) or {@code null}
 * @param to      period end (inclusive) or {@code null}
 */
public record ExportFilter(UUID storeId, Instant from, Instant to) {

    public static ExportFilter none() {
        return new ExportFilter(null, null, null);
    }
}
