package com.keevo.catalog.product.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * SalePriceOverriddenEvent — Domain event published when a salesperson overrides
 * the catalogue price during a point-of-sale transaction.
 *
 * <p>GoF Pattern: Observer — published via Spring's ApplicationEventPublisher,
 * consumed by {@link com.keevo.shared.infrastructure.web.AuditEventListener} for
 * audit trail compliance (Story 2.2 — Pricing Engine).
 *
 * <p>Note: This event is deferred to Epic 4 (POS) for full implementation, but
 * the audit handler is set up here as part of the pricing engine foundation.
 *
 * @param productId      ID of the product being sold
 * @param saleId         ID of the sale/transaction (nullable until Epic 4)
 * @param cataloguePrice the standard catalogue price in XAF
 * @param appliedPrice   the overridden price applied at point of sale in XAF
 * @param actorId        the salesperson who applied the override
 * @param tenantId       schema name (kv_xxxxxx) for tenant isolation
 * @param occurredAt     timestamp of the override event
 */
public record SalePriceOverriddenEvent(
        UUID productId,
        UUID saleId,
        int cataloguePrice,
        int appliedPrice,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
