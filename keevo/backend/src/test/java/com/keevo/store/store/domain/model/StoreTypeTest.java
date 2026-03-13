package com.keevo.store.store.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StoreTypeTest — Unit tests for StoreType enum (Story 3.1).
 */
@DisplayName("StoreType")
class StoreTypeTest {

    @Test
    @DisplayName("STORE type should have correct name")
    void storeType_store_shouldBeValid() {
        assertThat(StoreType.STORE.name()).isEqualTo("STORE");
    }

    @Test
    @DisplayName("WAREHOUSE type should have correct name")
    void storeType_warehouse_shouldBeValid() {
        assertThat(StoreType.WAREHOUSE.name()).isEqualTo("WAREHOUSE");
    }

    @Test
    @DisplayName("StoreType values should contain exactly STORE and WAREHOUSE")
    void storeType_shouldHaveExactlyTwoValues() {
        assertThat(StoreType.values()).hasSize(2);
        assertThat(StoreType.values()).containsExactlyInAnyOrder(StoreType.STORE, StoreType.WAREHOUSE);
    }
}
