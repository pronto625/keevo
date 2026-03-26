package com.keevo.inventory.counting.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InventorySessionStatus — State machine")
class InventorySessionStatusTest {

    @Test
    void inProgress_canCancel() {
        assertTrue(InventorySessionStatus.IN_PROGRESS.canCancel());
    }

    @Test
    void inProgress_canValidate() {
        assertTrue(InventorySessionStatus.IN_PROGRESS.canValidate());
    }

    @Test
    void validated_cannotCancel() {
        assertFalse(InventorySessionStatus.VALIDATED.canCancel());
    }

    @Test
    void validated_cannotValidate() {
        assertFalse(InventorySessionStatus.VALIDATED.canValidate());
    }

    @Test
    void cancelled_cannotCancel() {
        assertFalse(InventorySessionStatus.CANCELLED.canCancel());
    }

    @Test
    void cancelled_cannotValidate() {
        assertFalse(InventorySessionStatus.CANCELLED.canValidate());
    }
}
