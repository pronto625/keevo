package com.keevo.catalog.stock.domain.port.in;

import java.util.UUID;

/**
 * CompleteTransferCommand — input value for Step 2 of the two-step transfer flow.
 *
 * <p>The actor issues this command at the destination store to acknowledge
 * receipt of the goods and credit the destination stock level.
 *
 * <p>Story v1s-12-9: added {@code assignedStoreId} (nullable) — scopes EMPLOYEE
 * completion to their assigned store (FR36). OWNER tokens have null.
 *
 * GoF: Command pattern. Story 3.3.
 */
public record CompleteTransferCommand(
    UUID transferId,
    UUID actorId,
    UUID assignedStoreId
) {}
