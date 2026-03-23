package com.keevo.sync.sync.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SyncOperationResultTest {

    @Test
    void operationResult_withConflictData_preservesMap() {
        var data = Map.<String, Object>of("conflictType", "STOCK_NEGATIVE", "resultingStock", -1);
        var result = new SyncOperationResult("op-1", SyncOperationStatus.CONFLICT, "srv-1", "STOCK_NEGATIVE", data);

        assertThat(result.operationId()).isEqualTo("op-1");
        assertThat(result.status()).isEqualTo(SyncOperationStatus.CONFLICT);
        assertThat(result.serverEntityId()).isEqualTo("srv-1");
        assertThat(result.reason()).isEqualTo("STOCK_NEGATIVE");
        assertThat(result.conflictData()).containsEntry("resultingStock", -1);
    }

    @Test
    void operationResult_withoutConflictData_nullField() {
        var result = new SyncOperationResult("op-2", SyncOperationStatus.APPLIED, "srv-2", null);

        assertThat(result.conflictData()).isNull();
    }

    @Test
    void operationResult_backwardCompatibleConstructor_nullConflictData() {
        var result = new SyncOperationResult("op-3", SyncOperationStatus.APPLIED, null, null);

        assertThat(result.conflictData()).isNull();
    }
}
