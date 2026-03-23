package com.keevo.sync.sync.domain.model;

import java.time.Instant;

/**
 * SyncOverwrittenEvent — Published when a LWW overwrite is detected.
 */
public record SyncOverwrittenEvent(
        String operationId,
        String entityType,
        String entityId,
        Instant overwrittenTimestamp,
        Instant winnerTimestamp,
        String tenantId,
        Instant occurredAt
) {}
