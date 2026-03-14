package com.keevo.catalog.stock.domain.model;

import com.keevo.store.store.domain.model.StoreType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StoreStockSummaryTest — unit tests for StoreStockSummary composite root.
 * Story 3.2. TDD RED phase.
 */
class StoreStockSummaryTest {

    private static final UUID STORE_ID = UUID.randomUUID();

    @Test
    void summary_shouldHoldAllFields() {
        var summary = new StoreStockSummary(
            STORE_ID, "Boutique Centrale", StoreType.STORE,
            12, 500000L, 3
        );

        assertThat(summary.storeId()).isEqualTo(STORE_ID);
        assertThat(summary.storeName()).isEqualTo("Boutique Centrale");
        assertThat(summary.storeType()).isEqualTo(StoreType.STORE);
        assertThat(summary.productCount()).isEqualTo(12);
        assertThat(summary.totalValueXaf()).isEqualTo(500000L);
        assertThat(summary.lowStockCount()).isEqualTo(3);
    }

    @Test
    void summary_shouldComputeIsLowCount_correctly() {
        // lowStockCount is 0 when no products are low
        var summary = new StoreStockSummary(
            STORE_ID, "Entrepôt", StoreType.WAREHOUSE,
            5, 250000L, 0
        );
        assertThat(summary.lowStockCount()).isZero();
    }

    @Test
    void summary_shouldSupportWarehouseType() {
        var summary = new StoreStockSummary(
            STORE_ID, "Entrepôt Principal", StoreType.WAREHOUSE,
            20, 1_000_000L, 2
        );
        assertThat(summary.storeType()).isEqualTo(StoreType.WAREHOUSE);
    }
}
