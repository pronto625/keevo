package com.keevo.shared.event.catalog.product;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * ProductPriceChangedEvent — a product's catalogue selling price was changed (Story 17.7).
 *
 * <p>Published by {@code UpdateProductUseCase} (REST {@code PATCH /api/v1/products/{id}} and sync
 * {@code UPDATE_PRODUCT}) only when the price really changes, whatever the actor's role. Internal event, no API
 * contract. Consumed by the {@code messaging} owner notification (module {@code alerts}), which keeps EMPLOYEE
 * actors only; the audit trail still goes through {@link ProductUpdatedEvent}.
 *
 * @param oldPrice  catalogue price before the change, in XAF
 * @param newPrice  catalogue price after the change, in XAF
 * @param actorRole {@code "OWNER"} or {@code "EMPLOYEE"}
 */
public record ProductPriceChangedEvent(
        UUID productId,
        String productName,
        int oldPrice,
        int newPrice,
        UUID actorId,
        String actorRole,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
