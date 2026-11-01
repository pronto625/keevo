package com.keevo.sync.sync.adapter.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.keevo.shared.application.port.sync.PullScope;
import com.keevo.sync.sync.domain.model.SyncPullResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Pull page. Story 16.13 — additive {@code scope} ({@code storeId} = the employee's store or null,
 * {@code restricted}) and {@code reseed} (true on every page of a pull restarted from the beginning because the
 * device's scope changed); V1 clients ignore them.
 */
public record SyncPullResponseDto(
        Instant serverTimestamp,
        Map<String, List<Map<String, Object>>> entities,
        Map<String, Integer> counts,
        boolean hasMore,
        String nextCursor,
        ScopeDto scope,
        boolean reseed
) {
    /** {@code storeId} is always serialised, null included. */
    public record ScopeDto(@JsonInclude(JsonInclude.Include.ALWAYS) String storeId, boolean restricted) {
        static ScopeDto from(PullScope scope) {
            return new ScopeDto(scope.storeId() != null ? scope.storeId().toString() : null, scope.restricted());
        }
    }

    public static SyncPullResponseDto from(SyncPullResult result) {
        return new SyncPullResponseDto(
                result.serverTimestamp(),
                result.entities(),
                result.counts(),
                result.hasMore(),
                result.nextCursor(),
                ScopeDto.from(result.scope()),
                result.reseed()
        );
    }
}
