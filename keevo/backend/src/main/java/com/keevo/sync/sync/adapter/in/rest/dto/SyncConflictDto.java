package com.keevo.sync.sync.adapter.in.rest.dto;

import com.keevo.sync.sync.domain.model.SyncConflictsLogEntry;

import java.time.Instant;
import java.util.Map;

public record SyncConflictDto(
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
) {
    public static SyncConflictDto from(SyncConflictsLogEntry entry) {
        return new SyncConflictDto(
                entry.id(),
                entry.operationId(),
                entry.operationType(),
                entry.entityId(),
                entry.entityType(),
                entry.conflictType(),
                entry.strategy(),
                entry.conflictData(),
                entry.resolvedAt(),
                entry.actorId());
    }
}
