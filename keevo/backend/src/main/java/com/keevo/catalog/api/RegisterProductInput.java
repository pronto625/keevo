package com.keevo.catalog.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Input of {@link ProductCommandPort#register}: an ACTIVE product with a selling price only
 * (buy price, transport cost, stock and threshold start at 0).
 *
 * <p>The actor fields feed the {@code ProductCreatedEvent} published by the catalog
 * ({@code actorRole}, {@code actorName}, {@code storeName} nullable); {@code createdAt} is also
 * the event's {@code occurredAt}.
 */
public record RegisterProductInput(
        UUID id,
        String name,
        String sku,
        UUID categoryId,
        int price,
        Instant createdAt,
        UUID actorId,
        String actorRole,
        String actorName,
        String storeName
) {}
