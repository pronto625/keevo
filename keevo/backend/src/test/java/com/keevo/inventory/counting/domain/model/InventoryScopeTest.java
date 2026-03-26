package com.keevo.inventory.counting.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InventoryScope — Enum values")
class InventoryScopeTest {

    @Test
    void fullScope_shouldBeValid() {
        assertEquals("FULL", InventoryScope.FULL.name());
    }

    @Test
    void partialScope_shouldBeValid() {
        assertEquals("PARTIAL", InventoryScope.PARTIAL.name());
    }

    @Test
    void valueOf_shouldReturnCorrectValues() {
        assertEquals(InventoryScope.FULL, InventoryScope.valueOf("FULL"));
        assertEquals(InventoryScope.PARTIAL, InventoryScope.valueOf("PARTIAL"));
    }
}
