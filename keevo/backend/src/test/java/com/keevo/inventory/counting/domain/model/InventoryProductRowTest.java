package com.keevo.inventory.counting.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InventoryProductRow — record unit tests")
class InventoryProductRowTest {

    @Test
    void of_shouldComputeEcartWhenPhysicalPresent() {
        var row = InventoryProductRow.of(
                UUID.randomUUID(), "Savon", null, null, null, null, 100, 95);
        assertThat(row.ecart()).isEqualTo(-5);
        assertThat(row.theoreticalQty()).isEqualTo(100);
        assertThat(row.physicalQty()).isEqualTo(95);
    }

    @Test
    void of_shouldReturnNullEcartWhenPhysicalNull() {
        var row = InventoryProductRow.of(
                UUID.randomUUID(), "Savon", null, null, null, null, 100, null);
        assertThat(row.ecart()).isNull();
        assertThat(row.physicalQty()).isNull();
    }

    @Test
    void of_shouldPreservePhotoUrlAndVariant() {
        UUID variantId = UUID.randomUUID();
        var row = InventoryProductRow.of(
                UUID.randomUUID(), "T-shirt", "KEV-000001", "http://example.com/photo.jpg",
                variantId, "Taille M", 50, 48);
        assertThat(row.photoUrl()).isEqualTo("http://example.com/photo.jpg");
        assertThat(row.variantId()).isEqualTo(variantId);
        assertThat(row.variantLabel()).isEqualTo("Taille M");
        assertThat(row.ecart()).isEqualTo(-2);
    }
}
