package com.keevo.inventory.counting.domain.model;

/**
 * InventorySessionStatus — State machine for inventory session lifecycle.
 *
 * <p>GoF State pattern: each status knows which transitions are valid.
 * <ul>
 *   <li>IN_PROGRESS → VALIDATED or CANCELLED</li>
 *   <li>VALIDATED → terminal</li>
 *   <li>CANCELLED → terminal</li>
 * </ul>
 */
public enum InventorySessionStatus {
    IN_PROGRESS,
    VALIDATED,
    CANCELLED;

    public boolean canCancel() {
        return this == IN_PROGRESS;
    }

    public boolean canValidate() {
        return this == IN_PROGRESS;
    }
}
