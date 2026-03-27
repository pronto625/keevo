package com.keevo.inventory.counting.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryGapRowTest {

    @Test
    void isSurplus_whenEcartPositive_shouldReturnTrue() {
        var row = new InventoryGapRow(
                UUID.randomUUID(), "T-Shirt", "KEV-ABC123", null,
                null, null,
                5, 8, 3,
                5000, 15000L
        );
        assertThat(row.isSurplus()).isTrue();
        assertThat(row.isShortage()).isFalse();
        assertThat(row.isConcordant()).isFalse();
    }

    @Test
    void isShortage_whenEcartNegative_shouldReturnTrue() {
        var row = new InventoryGapRow(
                UUID.randomUUID(), "Robe Rouge", "KEV-DEF456", null,
                null, null,
                10, 5, -5,
                5000, 25000L
        );
        assertThat(row.isShortage()).isTrue();
        assertThat(row.isSurplus()).isFalse();
        assertThat(row.isConcordant()).isFalse();
    }

    @Test
    void isConcordant_whenEcartZero_shouldReturnTrue() {
        var row = new InventoryGapRow(
                UUID.randomUUID(), "Jeans", "KEV-GHI789", null,
                null, null,
                10, 10, 0,
                3000, 0L
        );
        assertThat(row.isConcordant()).isTrue();
        assertThat(row.isSurplus()).isFalse();
        assertThat(row.isShortage()).isFalse();
    }
}
