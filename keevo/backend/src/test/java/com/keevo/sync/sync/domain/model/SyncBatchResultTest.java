package com.keevo.sync.sync.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SyncBatchResultTest {

    @Test
    void batchResult_allApplied_returnsCorrectCounts() {
        var results = List.of(
                new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null),
                new SyncOperationResult("op-2", SyncOperationStatus.APPLIED, "srv-2", null)
        );
        var batch = new SyncBatchResult(Instant.now(), results);

        assertThat(batch.appliedCount()).isEqualTo(2);
        assertThat(batch.rejectedCount()).isEqualTo(0);
        assertThat(batch.results()).hasSize(2);
    }

    @Test
    void batchResult_mixedResults_returnsCorrectCounts() {
        var results = List.of(
                new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null),
                new SyncOperationResult("op-2", SyncOperationStatus.REJECTED, null, "INVALID"),
                new SyncOperationResult("op-3", SyncOperationStatus.DUPLICATE, null, null),
                new SyncOperationResult("op-4", SyncOperationStatus.CONFLICT, null, "CONFLICT_REASON")
        );
        var batch = new SyncBatchResult(Instant.now(), results);

        assertThat(batch.appliedCount()).isEqualTo(1);
        assertThat(batch.rejectedCount()).isEqualTo(1);
        assertThat(batch.results()).hasSize(4);
    }

    @Test
    void batchResult_emptyBatch_returnsZeroCounts() {
        var batch = new SyncBatchResult(Instant.now(), List.of());

        assertThat(batch.appliedCount()).isEqualTo(0);
        assertThat(batch.rejectedCount()).isEqualTo(0);
        assertThat(batch.results()).isEmpty();
    }
}
