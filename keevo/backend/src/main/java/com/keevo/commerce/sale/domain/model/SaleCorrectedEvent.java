package com.keevo.commerce.sale.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * SaleCorrectedEvent — published when a COMPLETED sale's item quantities are corrected.
 * Story v1s-13-5 (AC6). {@code beforeSnapshot}/{@code afterSnapshot} carry the sale items
 * as JSON, mirroring the {@code itemsSnapshot} pattern used by {@link SaleCompletedEvent}
 * and {@link SaleCancelledEvent}.
 */
public record SaleCorrectedEvent(
        UUID saleId,
        UUID actorId,
        String justification,
        String beforeSnapshot,
        String afterSnapshot,
        String tenantId,
        Instant occurredAt
) {}
