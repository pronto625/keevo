package com.keevo.catalog.stock.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * TransferCreatedEvent — published by {@link com.keevo.catalog.stock.application.usecase.ExecuteTransferService}
 * when a new IN_TRANSIT transfer is persisted (Step 1 of the two-step flow).
 *
 * <p>Distinct from {@link StockTransferredEvent}, which is published at both
 * Step 1 and Step 2 for the audit listener. This event targets the notification
 * layer only and carries all recipient-resolution data.
 *
 * <p>GoF: Observer — {@code TransferCreatedNotificationListener} reacts to this
 * event without coupling the transfer domain to the notification module.
 *
 * Story HF-2 AC2.
 */
public record TransferCreatedEvent(
        UUID transferId,
        UUID sourceStoreId,
        UUID destinationStoreId,
        UUID productId,
        int quantity,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
