package com.keevo.sync.sync.adapter.in.rest.dto;

import com.keevo.sync.sync.domain.model.SyncBatchResult;

import java.time.Instant;
import java.util.List;

public record SyncPushResponseDto(
        Instant processedAt,
        List<SyncOperationResultDto> results
) {
    public static SyncPushResponseDto from(SyncBatchResult result) {
        return new SyncPushResponseDto(
                result.processedAt(),
                result.results().stream()
                        .map(SyncOperationResultDto::from)
                        .toList());
    }
}
