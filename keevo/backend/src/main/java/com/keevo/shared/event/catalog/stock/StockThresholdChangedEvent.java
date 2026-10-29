package com.keevo.shared.event.catalog.stock;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * StockThresholdChangedEvent — published after the owner changes a product's low-stock
 * alert threshold ({@code SetStockThresholdUseCase}). Consumed by AuditEventListener
 * (STOCK_THRESHOLD_UPDATED).
 */
public record StockThresholdChangedEvent(
        UUID productId,
        String productName,
        int previousThreshold,
        int newThreshold,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
