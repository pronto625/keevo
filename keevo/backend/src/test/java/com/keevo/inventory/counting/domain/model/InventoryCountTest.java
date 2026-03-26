package com.keevo.inventory.counting.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InventoryCount — domain entity unit tests")
class InventoryCountTest {

    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID VARIANT_ID = UUID.randomUUID();
    private static final UUID ACTOR_ID = UUID.randomUUID();

    @Nested
    @DisplayName("create()")
    class CreateTests {

        @Test
        void shouldCreateWithAllFields() {
            InventoryCount count = InventoryCount.create(
                    SESSION_ID, PRODUCT_ID, VARIANT_ID,
                    "T-shirt", "Rouge XL", 50, 47, ACTOR_ID);

            assertThat(count.getId()).isNotNull();
            assertThat(count.getSessionId()).isEqualTo(SESSION_ID);
            assertThat(count.getProductId()).isEqualTo(PRODUCT_ID);
            assertThat(count.getVariantId()).isEqualTo(VARIANT_ID);
            assertThat(count.getProductName()).isEqualTo("T-shirt");
            assertThat(count.getVariantLabel()).isEqualTo("Rouge XL");
            assertThat(count.getTheoretical()).isEqualTo(50);
            assertThat(count.getPhysical()).isEqualTo(47);
            assertThat(count.getCountedAt()).isNotNull();
            assertThat(count.getCountedBy()).isEqualTo(ACTOR_ID);
        }

        @Test
        void shouldCreateWithNullVariant() {
            InventoryCount count = InventoryCount.create(
                    SESSION_ID, PRODUCT_ID, null,
                    "Simple product", null, 10, 8, ACTOR_ID);

            assertThat(count.getVariantId()).isNull();
            assertThat(count.getVariantLabel()).isNull();
        }
    }

    @Nested
    @DisplayName("getEcart()")
    class EcartTests {

        @Test
        void shouldReturnPositiveEcartWhenPhysicalGreaterThanTheoretical() {
            InventoryCount count = InventoryCount.create(
                    SESSION_ID, PRODUCT_ID, null, "P", null, 10, 15, ACTOR_ID);
            assertThat(count.getEcart()).isEqualTo(5);
        }

        @Test
        void shouldReturnNegativeEcartWhenPhysicalLessThanTheoretical() {
            InventoryCount count = InventoryCount.create(
                    SESSION_ID, PRODUCT_ID, null, "P", null, 50, 47, ACTOR_ID);
            assertThat(count.getEcart()).isEqualTo(-3);
        }

        @Test
        void shouldReturnZeroEcartWhenEqual() {
            InventoryCount count = InventoryCount.create(
                    SESSION_ID, PRODUCT_ID, null, "P", null, 25, 25, ACTOR_ID);
            assertThat(count.getEcart()).isEqualTo(0);
        }

        @Test
        void shouldThrowIfPhysicalIsNull() {
            InventoryCount count = new InventoryCount(
                    UUID.randomUUID(), SESSION_ID, PRODUCT_ID, null,
                    "P", null, 10, null, null, null, Instant.now());
            assertThatThrownBy(count::getEcart)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("isCounted()")
    class IsCountedTests {

        @Test
        void shouldReturnTrueWhenPhysicalSet() {
            InventoryCount count = InventoryCount.create(
                    SESSION_ID, PRODUCT_ID, null, "P", null, 10, 8, ACTOR_ID);
            assertThat(count.isCounted()).isTrue();
        }

        @Test
        void shouldReturnFalseWhenPhysicalNull() {
            InventoryCount count = new InventoryCount(
                    UUID.randomUUID(), SESSION_ID, PRODUCT_ID, null,
                    "P", null, 10, null, null, null, Instant.now());
            assertThat(count.isCounted()).isFalse();
        }
    }

    @Test
    @DisplayName("withPhysical() should return new instance with updated values")
    void withPhysical_shouldReturnNewInstance() {
        InventoryCount original = InventoryCount.create(
                SESSION_ID, PRODUCT_ID, null, "P", null, 10, 8, ACTOR_ID);

        UUID newActor = UUID.randomUUID();
        InventoryCount updated = original.withPhysical(12, newActor);

        assertThat(updated.getId()).isEqualTo(original.getId());
        assertThat(updated.getPhysical()).isEqualTo(12);
        assertThat(updated.getCountedBy()).isEqualTo(newActor);
        // Original unchanged
        assertThat(original.getPhysical()).isEqualTo(8);
    }
}
