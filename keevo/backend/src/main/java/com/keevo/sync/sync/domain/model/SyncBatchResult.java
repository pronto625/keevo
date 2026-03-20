package com.keevo.sync.sync.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * SyncBatchResult — Aggregate result for a batch push.
 */
public record SyncBatchResult(
        Instant processedAt,
        List<SyncOperationResult> results
) {
    public long appliedCount() {
        return results.stream().filter(r -> r.status() == SyncOperationStatus.APPLIED).count();
    }

    public long rejectedCount() {
        return results.stream().filter(r -> r.status() == SyncOperationStatus.REJECTED).count();
    }
}
