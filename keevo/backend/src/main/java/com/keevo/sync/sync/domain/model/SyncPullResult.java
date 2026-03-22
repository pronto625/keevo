package com.keevo.sync.sync.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SyncPullResult — Immutable domain model representing the result of a pull sync operation.
 *
 * Contains all entity deltas queried since the given timestamp, along with per-entity counts
 * and the server timestamp to use as the cursor for the next pull.
 */
public record SyncPullResult(
        Instant serverTimestamp,
        Map<String, List<Map<String, Object>>> entities,
        Map<String, Integer> counts
) {
    public SyncPullResult {
        Objects.requireNonNull(serverTimestamp, "serverTimestamp required");
        entities = entities != null ? entities : Map.of();
        counts = counts != null ? counts : Map.of();
    }
}
