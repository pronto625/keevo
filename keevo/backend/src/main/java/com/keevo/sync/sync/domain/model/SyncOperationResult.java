package com.keevo.sync.sync.domain.model;

/**
 * SyncOperationResult — Outcome of processing a single sync operation.
 */
public record SyncOperationResult(
        String operationId,
        SyncOperationStatus status,
        String serverEntityId,
        String reason
) {}
