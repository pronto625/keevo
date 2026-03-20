package com.keevo.sync.sync.domain.model;

import java.time.Instant;

/**
 * SyncOperationsLogEntry — Record of a processed sync operation for idempotency.
 */
public record SyncOperationsLogEntry(
        String id,
        String operationType,
        String entityId,
        SyncOperationStatus status,
        String errorReason,
        Instant processedAt,
        Instant clientTimestamp
) {}
