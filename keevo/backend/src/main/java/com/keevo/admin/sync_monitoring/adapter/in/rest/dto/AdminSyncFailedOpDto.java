package com.keevo.admin.sync_monitoring.adapter.in.rest.dto;

import com.keevo.admin.sync_monitoring.domain.model.AdminSyncFailedOp;

import java.time.Instant;

public record AdminSyncFailedOpDto(
        String operationType,
        String errorReason,
        Instant processedAt
) {
    public static AdminSyncFailedOpDto from(AdminSyncFailedOp model) {
        return new AdminSyncFailedOpDto(
                model.operationType(),
                model.errorReason(),
                model.processedAt());
    }
}
