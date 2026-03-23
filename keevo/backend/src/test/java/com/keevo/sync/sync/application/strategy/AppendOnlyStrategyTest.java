package com.keevo.sync.sync.application.strategy;

import com.keevo.sync.sync.domain.model.SyncOperation;
import com.keevo.sync.sync.domain.model.SyncOperationResult;
import com.keevo.sync.sync.domain.model.SyncOperationStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AppendOnlyStrategyTest {

    private final AppendOnlyStrategy strategy = new AppendOnlyStrategy();

    @Test
    void evaluate_always_returnsNull() {
        var op = new SyncOperation("op-1", "CREATE_DAY_CLOSURE", "e1", Map.of(), Instant.now());
        var handlerResult = new SyncOperationResult("op-1", SyncOperationStatus.APPLIED, "srv-1", null);

        var result = strategy.evaluate(op, handlerResult, UUID.randomUUID(), "kv_abc123");

        assertThat(result).isNull();
    }

    @Test
    void evaluate_anyOperationType_returnsNull() {
        var op = new SyncOperation("op-2", "CREATE_SALE", "e2", Map.of(), Instant.now());
        var handlerResult = new SyncOperationResult("op-2", SyncOperationStatus.APPLIED, "srv-2", null);

        var result = strategy.evaluate(op, handlerResult, UUID.randomUUID(), "kv_test01");

        assertThat(result).isNull();
    }
}
