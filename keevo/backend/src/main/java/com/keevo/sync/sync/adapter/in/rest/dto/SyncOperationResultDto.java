package com.keevo.sync.sync.adapter.in.rest.dto;

import com.keevo.sync.sync.domain.model.SyncOperationResult;

public record SyncOperationResultDto(
        String operationId,
        String status,
        String serverEntityId,
        String reason
) {
    public static SyncOperationResultDto from(SyncOperationResult result) {
        return new SyncOperationResultDto(
                result.operationId(),
                result.status().name(),
                result.serverEntityId(),
                result.reason());
    }
}
