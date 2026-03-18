package com.keevo.commerce.sale.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * SaleCancelledEvent — published when a pending sale is cancelled by the owner.
 * Story 4.3 AC10.
 */
public record SaleCancelledEvent(
        UUID saleId,
        UUID actorId,
        String justification,
        String tenantId,
        Instant occurredAt
) {}
