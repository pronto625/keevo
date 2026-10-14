package com.keevo.sync.sync.domain.model;

import java.time.Instant;

/**
 * SyncOperationsLogEntry — Record of a processed sync operation for idempotency.
 *
 * <p>{@code deviceId}: device that pushed the operation; {@code null} for rows logged before it
 * was recorded. Last-write-wins uses it to tell sequential edits of one device from a concurrent
 * overwrite by another device.
 */
public record SyncOperationsLogEntry(
        String id,
        String operationType,
        String entityId,
        SyncOperationStatus status,
        String errorReason,
        Instant processedAt,
        Instant clientTimestamp,
        String deviceId
) {
    /** Entry whose pushing device is unknown. */
    public SyncOperationsLogEntry(String id, String operationType, String entityId,
                                  SyncOperationStatus status, String errorReason,
                                  Instant processedAt, Instant clientTimestamp) {
        this(id, operationType, entityId, status, errorReason, processedAt, clientTimestamp, null);
    }
}
