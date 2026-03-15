package com.keevo.catalog.stock.domain.port.in;

import java.util.UUID;

/**
 * CompleteTransferCommand — input value for Step 2 of the two-step transfer flow.
 *
 * <p>The actor issues this command at the destination store to acknowledge
 * receipt of the goods and credit the destination stock level.
 *
 * GoF: Command pattern. Story 3.3.
 */
public record CompleteTransferCommand(
    UUID transferId,
    UUID actorId
) {}
