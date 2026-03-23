package com.keevo.sync.sync.domain.model;

import java.util.Map;

/**
 * SyncOperationResult — Outcome of processing a single sync operation.
 */
public record SyncOperationResult(
        String operationId,
        SyncOperationStatus status,
        String serverEntityId,
        String reason,
        Map<String, Object> conflictData
) {
    /** Backward-compatible constructor — null conflictData. */
    public SyncOperationResult(String operationId, SyncOperationStatus status,
                               String serverEntityId, String reason) {
        this(operationId, status, serverEntityId, reason, null);
    }
}
