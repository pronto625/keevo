package com.keevo.shared.application.port.export;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Export filters (Story v1s-18-1, criteria v1s-18-2). Every field is optional; {@code from} and
 * {@code to} are inclusive bounds on the dataset's period column.
 *
 * <p>A dataset applies only the filters it declares ({@link ExportDatasetProvider#supportsStoreFilter()},
 * {@link ExportDatasetProvider#supportsPeriodFilter()}, {@link ExportDatasetProvider#criteriaKeys()}).
 *
 * @param storeId  one store, or {@code null} for all stores
 * @param from     period start (inclusive) or {@code null}
 * @param to       period end (inclusive) or {@code null}
 * @param criteria dataset-specific criteria (e.g. the audit log's {@code userId}, {@code action},
 *                 {@code entityType}, {@code entityId}, {@code q}); never {@code null}, unmodifiable,
 *                 null keys/values dropped, sorted by key
 */
public record ExportFilter(UUID storeId, Instant from, Instant to, Map<String, String> criteria) {

    public ExportFilter {
        TreeMap<String, String> copy = new TreeMap<>();
        if (criteria != null) {
            criteria.forEach((k, v) -> {
                if (k != null && v != null) copy.put(k, v);
            });
        }
        criteria = Collections.unmodifiableMap(copy);
    }

    /** Store / period only (no criteria). */
    public ExportFilter(UUID storeId, Instant from, Instant to) {
        this(storeId, from, to, Map.of());
    }

    public static ExportFilter none() {
        return new ExportFilter(null, null, null);
    }

    /** Value of one criterion, or {@code null} when absent. */
    public String criterion(String key) {
        return criteria.get(key);
    }
}
