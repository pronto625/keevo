package com.keevo.admin.sync_monitoring.adapter.in.rest.dto;

import com.keevo.admin.sync_monitoring.domain.model.AdminSyncConflict;

import java.time.Instant;

public record AdminSyncConflictDto(
        String entityType,
        String conflictType,
        String strategy,
        Instant resolvedAt
) {
    public static AdminSyncConflictDto from(AdminSyncConflict model) {
        return new AdminSyncConflictDto(
                model.entityType(),
                model.conflictType(),
                model.strategy(),
                model.resolvedAt());
    }
}
