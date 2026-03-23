package com.keevo.sync.sync.domain.model;

import java.time.Instant;

/**
 * StockConflictResolvedEvent — Published when a stock delta resolution is evaluated.
 */
public record StockConflictResolvedEvent(
        String operationId,
        String productId,
        String storeId,
        int deltaApplied,
        int resultingStock,
        int previousStock,
        String strategy,
        boolean isNegativeConflict,
        String tenantId,
        Instant occurredAt
) {}
