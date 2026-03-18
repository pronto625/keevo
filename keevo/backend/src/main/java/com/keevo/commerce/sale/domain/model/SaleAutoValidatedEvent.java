package com.keevo.commerce.sale.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * SaleAutoValidatedEvent — published when a pending sale is auto-validated
 * because all referenced products transitioned to ACTIVE with stock > 0.
 * Story 4.3 AC7.
 */
public record SaleAutoValidatedEvent(
        UUID saleId,
        UUID triggerProductId,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
