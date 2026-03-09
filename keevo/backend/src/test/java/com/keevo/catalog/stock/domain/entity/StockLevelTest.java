package com.keevo.catalog.stock.domain.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StockLevelTest — unit tests for StockLevel domain entity.
 * Story 2.3.
 */
class StockLevelTest {

    private static final UUID ID         = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID STORE_ID   = UUID.randomUUID();

    @Test
    void should_create_stock_level_with_valid_values() {
        var level = new StockLevel(ID, PRODUCT_ID, null, STORE_ID, 10, Instant.now());

        assertThat(level.getId()).isEqualTo(ID);
        assertThat(level.getProductId()).isEqualTo(PRODUCT_ID);
        assertThat(level.getVariantId()).isNull();
        assertThat(level.getStoreId()).isEqualTo(STORE_ID);
        assertThat(level.getQuantity()).isEqualTo(10);
    }

    @Test
    void should_create_stock_level_with_zero_quantity() {
        var level = new StockLevel(ID, PRODUCT_ID, null, STORE_ID, 0, Instant.now());
        assertThat(level.getQuantity()).isEqualTo(0);
    }

    @Test
    void should_return_new_instance_with_updated_quantity_via_withQuantity() {
        var original = new StockLevel(ID, PRODUCT_ID, null, STORE_ID, 5, Instant.now());
        var updated  = original.withQuantity(20);

        // Returns a new object
        assertThat(updated).isNotSameAs(original);
        assertThat(updated.getQuantity()).isEqualTo(20);

        // Original unchanged (immutability)
        assertThat(original.getQuantity()).isEqualTo(5);

        // Other fields preserved
        assertThat(updated.getId()).isEqualTo(original.getId());
        assertThat(updated.getProductId()).isEqualTo(original.getProductId());
        assertThat(updated.getStoreId()).isEqualTo(original.getStoreId());
    }

    @Test
    void should_accept_variant_id() {
        UUID variantId = UUID.randomUUID();
        var level = new StockLevel(ID, PRODUCT_ID, variantId, STORE_ID, 3, Instant.now());
        assertThat(level.getVariantId()).isEqualTo(variantId);
    }
}
