package com.keevo.sync.sync.domain.model;

import com.keevo.shared.application.port.sync.SyncOperationStatus;
import java.time.Instant;

/**
 * SyncOperationProcessedEvent — Published after each sync operation is processed.
 */
public record SyncOperationProcessedEvent(
        String operationId,
        String operationType,
        String entityId,
        SyncOperationStatus status,
        String tenantId,
        Instant occurredAt
) {}
