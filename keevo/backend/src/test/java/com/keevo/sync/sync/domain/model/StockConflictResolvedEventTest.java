package com.keevo.sync.sync.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StockConflictResolvedEventTest {

    @Test
    void event_create_setsAllFields() {
        var event = new StockConflictResolvedEvent(
                "op-1", "p1", "s1", -3, -1, 2, "DELTA_SUM", true, "kv_abc123", Instant.now());

        assertThat(event.operationId()).isEqualTo("op-1");
        assertThat(event.productId()).isEqualTo("p1");
        assertThat(event.storeId()).isEqualTo("s1");
        assertThat(event.deltaApplied()).isEqualTo(-3);
        assertThat(event.resultingStock()).isEqualTo(-1);
        assertThat(event.previousStock()).isEqualTo(2);
        assertThat(event.strategy()).isEqualTo("DELTA_SUM");
        assertThat(event.isNegativeConflict()).isTrue();
        assertThat(event.tenantId()).isEqualTo("kv_abc123");
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void event_negativeStock_includesDeltaAndResult() {
        var event = new StockConflictResolvedEvent(
                "op-2", "p2", "s2", -5, -3, 2, "DELTA_SUM", true, "kv_xyz789", Instant.now());

        assertThat(event.isNegativeConflict()).isTrue();
        assertThat(event.resultingStock()).isNegative();
        assertThat(event.previousStock() + event.deltaApplied()).isEqualTo(event.resultingStock());
    }

    @Test
    void event_positiveStock_noConflictType() {
        var event = new StockConflictResolvedEvent(
                "op-3", "p3", "s3", -2, 8, 10, "DELTA_SUM", false, "kv_test01", Instant.now());

        assertThat(event.isNegativeConflict()).isFalse();
        assertThat(event.resultingStock()).isPositive();
    }
}
