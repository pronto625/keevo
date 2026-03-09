package com.keevo.catalog.stock.domain.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StockMovementTest — verifies domain invariants in StockMovement.
 * Story 2.3.
 */
class StockMovementTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID STORE_ID   = UUID.randomUUID();
    private static final UUID ACTOR_ID   = UUID.randomUUID();

    @Test
    void should_create_movement_satisfying_invariant() {
        var movement = new StockMovement(
            UUID.randomUUID(), PRODUCT_ID, null, STORE_ID,
            MovementType.STOCK_ENTRY,
            0, 10, 10,  // 0 + 10 = 10 ✓
            ACTOR_ID, null, Instant.now()
        );
        assertThat(movement.getQuantityAfter())
            .isEqualTo(movement.getQuantityBefore() + movement.getQuantityChange());
    }

    @Test
    void should_reject_movement_violating_quantity_invariant() {
        assertThatThrownBy(() -> new StockMovement(
            UUID.randomUUID(), PRODUCT_ID, null, STORE_ID,
            MovementType.ADJUSTMENT,
            5, 3, 10,  // 5 + 3 ≠ 10  ✗
            ACTOR_ID, null, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("invariant violated");
    }

    @Test
    void should_accept_negative_quantity_change_for_sale() {
        var movement = new StockMovement(
            UUID.randomUUID(), PRODUCT_ID, null, STORE_ID,
            MovementType.SALE,
            20, -5, 15,  // 20 + (-5) = 15 ✓
            ACTOR_ID, "POS sale", Instant.now()
        );
        assertThat(movement.getQuantityAfter()).isEqualTo(15);
        assertThat(movement.getQuantityChange()).isNegative();
    }

    @Test
    void should_reject_null_required_fields() {
        assertThatThrownBy(() -> new StockMovement(
            UUID.randomUUID(), null, null, STORE_ID,
            MovementType.ADJUSTMENT, 0, 0, 0, ACTOR_ID, null, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("productId");

        assertThatThrownBy(() -> new StockMovement(
            UUID.randomUUID(), PRODUCT_ID, null, null,
            MovementType.ADJUSTMENT, 0, 0, 0, ACTOR_ID, null, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("storeId");

        assertThatThrownBy(() -> new StockMovement(
            UUID.randomUUID(), PRODUCT_ID, null, STORE_ID,
            MovementType.STOCK_ENTRY, 0, 10, 10, null, null, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("actorId");
    }

    @Test
    void should_preserve_optional_notes() {
        var movement = new StockMovement(
            UUID.randomUUID(), PRODUCT_ID, null, STORE_ID,
            MovementType.STOCK_ENTRY, 0, 5, 5,
            ACTOR_ID, "BL-2025-001", Instant.now()
        );
        assertThat(movement.getNotes()).isEqualTo("BL-2025-001");
    }
}
