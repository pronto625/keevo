package com.keevo.reporting.export.domain.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DataExportedEvent — published on the request thread for every accepted export, direct or
 * background (Story v1s-18-1). Audited by {@code AuditEventListener} as {@code DATA_EXPORTED}.
 *
 * @param datasetLabels French labels of {@code datasets}, same order
 * @param criteria      dataset-specific criteria of the request (Story v1s-18-2), never null
 * @param rowCount      rows written (DIRECT / DIRECT_INCOMPLETE) or counted at request time (BACKGROUND)
 * @param mode          {@link #MODE_DIRECT}, {@link #MODE_DIRECT_INCOMPLETE} (stream failed or was
 *                      interrupted — {@code rowCount} = rows written so far) or {@link #MODE_BACKGROUND}
 */
public record DataExportedEvent(
        UUID exportId,
        UUID actorId,
        String tenantId,
        String format,
        List<String> datasets,
        List<String> datasetLabels,
        UUID storeId,
        Instant from,
        Instant to,
        Map<String, String> criteria,
        long rowCount,
        String mode,
        Instant occurredAt
) {
    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_DIRECT_INCOMPLETE = "DIRECT_INCOMPLETE";
    public static final String MODE_BACKGROUND = "BACKGROUND";

    public DataExportedEvent {
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
        datasetLabels = datasetLabels == null ? List.of() : List.copyOf(datasetLabels);
        criteria = criteria == null ? Map.of() : Map.copyOf(criteria);
    }

    /** v1s-18-1 shape — no criteria. */
    public DataExportedEvent(UUID exportId, UUID actorId, String tenantId, String format, List<String> datasets,
                             List<String> datasetLabels, UUID storeId, Instant from, Instant to, long rowCount,
                             String mode, Instant occurredAt) {
        this(exportId, actorId, tenantId, format, datasets, datasetLabels, storeId, from, to, Map.of(), rowCount,
                mode, occurredAt);
    }
}
