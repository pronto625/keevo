package com.keevo.catalog.product.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a product photo is added, replaced or removed — audit action
 * {@code PRODUCT_PHOTO_UPDATED} (« Photo du produit modifiée »).
 *
 * @param hadPhoto whether the product had a photo before
 * @param hasPhoto whether it has one after ({@code false} = removed)
 */
public record ProductPhotoUpdatedEvent(
        UUID productId,
        String productName,
        boolean hadPhoto,
        boolean hasPhoto,
        String tenantId,
        UUID actorId,
        String actorName,
        Instant occurredAt
) {}
