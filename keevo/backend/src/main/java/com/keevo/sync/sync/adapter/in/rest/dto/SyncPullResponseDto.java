package com.keevo.sync.sync.adapter.in.rest.dto;

import com.keevo.sync.sync.domain.model.SyncPullResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SyncPullResponseDto(
        Instant serverTimestamp,
        Map<String, List<Map<String, Object>>> entities,
        Map<String, Integer> counts
) {
    public static SyncPullResponseDto from(SyncPullResult result) {
        return new SyncPullResponseDto(
                result.serverTimestamp(),
                result.entities(),
                result.counts()
        );
    }
}
