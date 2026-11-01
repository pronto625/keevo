package com.keevo.sync.sync.adapter.in.rest.dto;

import com.keevo.shared.application.port.sync.SyncOperationResult;

import java.util.Map;

/**
 * One operation's outcome in a sync push answer.
 *
 * @param conflictData {@code APPLIED} / {@code CONFLICT}: the conflict details (e.g. {@code conflictType}
 *                     {@code LAST_WRITE_SUPERSEDED}, {@code STOCK_NEGATIVE}); {@code REJECTED} with {@code reason}
 *                     {@code PLAN_LIMIT_EXCEEDED} (Story 16.14): the same details as the REST 403 —
 *                     {@code {entity, limit, current, plan}}; {@code null} for every other rejection
 */
public record SyncOperationResultDto(
        String operationId,
        String status,
        String serverEntityId,
        String reason,
        Map<String, Object> conflictData
) {
    public static SyncOperationResultDto from(SyncOperationResult result) {
        return new SyncOperationResultDto(
                result.operationId(),
                result.status().name(),
                result.serverEntityId(),
                result.reason(),
                result.conflictData());
    }
}
