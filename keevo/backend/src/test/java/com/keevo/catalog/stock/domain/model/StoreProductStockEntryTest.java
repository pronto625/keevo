package com.keevo.catalog.stock.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StoreProductStockEntryTest — unit tests for StoreProductStockEntry composite leaf.
 * Story 3.2. TDD RED phase.
 */
class StoreProductStockEntryTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID STORE_ID   = UUID.randomUUID();

    private StoreProductStockEntry entry(int qty, int threshold) {
        return new StoreProductStockEntry(
            PRODUCT_ID, "Chaussures Nike", null, null,
            STORE_ID, qty, threshold
        );
    }

    @Test
    void entry_isLow_whenQuantityBelowThreshold() {
        var e = entry(3, 5);
        assertThat(e.isLow()).isTrue();
        assertThat(e.status()).isEqualTo(StockStatus.BAS);
    }

    @Test
    void entry_isLow_whenQuantityEqualsThreshold() {
        var e = entry(5, 5);
        assertThat(e.isLow()).isTrue();
        assertThat(e.status()).isEqualTo(StockStatus.BAS);
    }

    @Test
    void entry_isCritical_whenQuantityIsZero() {
        var e = entry(0, 5);
        assertThat(e.isCritical()).isTrue();
        assertThat(e.status()).isEqualTo(StockStatus.CRITIQUE);
    }

    @Test
    void entry_isCritical_overridesIsLow() {
        // When qty=0 and threshold>0, CRITIQUE takes priority
        var e = entry(0, 10);
        assertThat(e.isCritical()).isTrue();
        assertThat(e.isLow()).isFalse(); // qty=0: isLow cannot be true (0 <= 10 but critical first)
        assertThat(e.status()).isEqualTo(StockStatus.CRITIQUE);
    }

    @Test
    void entry_isNormal_whenThresholdIsZero() {
        var e = entry(2, 0);
        assertThat(e.isLow()).isFalse();
        assertThat(e.isCritical()).isFalse();
        assertThat(e.status()).isEqualTo(StockStatus.NORMAL);
    }

    @Test
    void entry_isNormal_whenAboveThreshold() {
        var e = entry(10, 5);
        assertThat(e.isLow()).isFalse();
        assertThat(e.isCritical()).isFalse();
        assertThat(e.status()).isEqualTo(StockStatus.NORMAL);
    }

    @Test
    void entry_holdAllFields() {
        var variantId = UUID.randomUUID();
        var e = new StoreProductStockEntry(
            PRODUCT_ID, "Chemise", variantId, "Taille L / Bleu",
            STORE_ID, 8, 10
        );
        assertThat(e.productId()).isEqualTo(PRODUCT_ID);
        assertThat(e.productName()).isEqualTo("Chemise");
        assertThat(e.variantId()).isEqualTo(variantId);
        assertThat(e.variantLabel()).isEqualTo("Taille L / Bleu");
        assertThat(e.storeId()).isEqualTo(STORE_ID);
        assertThat(e.quantity()).isEqualTo(8);
        assertThat(e.minimumThreshold()).isEqualTo(10);
    }
}
