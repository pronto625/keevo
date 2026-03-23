package com.keevo.sync.sync.domain.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConflictResultTest {

    @Test
    void conflictResult_create_setsAllFields() {
        var data = Map.<String, Object>of("productId", "p1", "resultingStock", -1);
        var result = new ConflictResult("STOCK_NEGATIVE", "DELTA_SUM", data);

        assertThat(result.conflictType()).isEqualTo("STOCK_NEGATIVE");
        assertThat(result.strategy()).isEqualTo("DELTA_SUM");
        assertThat(result.conflictData()).containsEntry("productId", "p1");
    }

    @Test
    void conflictResult_noConflict_nullConflictType() {
        var result = new ConflictResult(null, "DELTA_SUM", null);

        assertThat(result.conflictType()).isNull();
        assertThat(result.strategy()).isEqualTo("DELTA_SUM");
        assertThat(result.conflictData()).isNull();
    }

    @Test
    void conflictResult_stockNegative_hasConflictData() {
        var data = Map.<String, Object>of(
                "conflictType", "STOCK_NEGATIVE",
                "productId", "p1",
                "productName", "Coca-Cola",
                "storeId", "s1",
                "deltaApplied", -3,
                "resultingStock", -1,
                "previousStock", 2);
        var result = new ConflictResult("STOCK_NEGATIVE", "DELTA_SUM", data);

        assertThat(result.conflictData()).hasSize(7);
        assertThat(result.conflictData()).containsEntry("resultingStock", -1);
        assertThat(result.conflictData()).containsEntry("previousStock", 2);
    }
}
