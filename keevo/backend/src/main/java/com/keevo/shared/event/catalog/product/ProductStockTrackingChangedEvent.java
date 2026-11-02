package com.keevo.shared.event.catalog.product;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * ProductStockTrackingChangedEvent — the owner turned a product's stock tracking on or off (Story 17.12).
 *
 * <p>Published only for a real change (same value → no event). Consumed by
 * {@link com.keevo.shared.infrastructure.web.AuditEventListener}: « Suivi du stock activé » /
 * « Suivi du stock désactivé ».
 *
 * @param tracked the new setting
 */
public record ProductStockTrackingChangedEvent(
        UUID productId,
        String productName,
        boolean tracked,
        String tenantId,
        UUID actorId,
        Instant occurredAt
) implements DomainEvent {}
