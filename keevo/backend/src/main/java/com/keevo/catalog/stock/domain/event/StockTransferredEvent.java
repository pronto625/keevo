package com.keevo.catalog.stock.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * StockTransferredEvent — domain event published after a successful transfer.
 *
 * <p>GoF: Observer — AuditEventListener reacts to this event to write the
 * audit log entry without coupling the domain service to the audit module.
 * Story 3.3.
 */
public record StockTransferredEvent(
    UUID transferId,
    UUID sourceStoreId,
    UUID destinationStoreId,
    UUID productId,
    UUID variantId,       // nullable
    int quantity,
    UUID actorId,
    String tenantId,
    Instant occurredAt
) {}
