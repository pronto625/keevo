package com.keevo.sync.sync.domain.model;

import java.time.Instant;
import java.util.Map;

/**
 * SyncConflictsLogEntry — Domain model for a conflict event persisted to sync_conflicts_log.
 */
public record SyncConflictsLogEntry(
        String id,
        String operationId,
        String operationType,
        String entityId,
        String entityType,
        String conflictType,
        String strategy,
        Map<String, Object> conflictData,
        Instant resolvedAt,
        String actorId
) {}
