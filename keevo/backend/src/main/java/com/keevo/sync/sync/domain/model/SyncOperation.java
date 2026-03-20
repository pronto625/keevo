package com.keevo.sync.sync.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * SyncOperation — A single operation from the client's sync queue.
 * Command pattern: self-contained with type + payload.
 */
public record SyncOperation(
        String operationId,
        String operationType,
        String entityId,
        Map<String, Object> payload,
        Instant clientTimestamp
) {
    public SyncOperation {
        Objects.requireNonNull(operationId, "operationId required");
        Objects.requireNonNull(operationType, "operationType required");
        Objects.requireNonNull(payload, "payload required");
    }
}
