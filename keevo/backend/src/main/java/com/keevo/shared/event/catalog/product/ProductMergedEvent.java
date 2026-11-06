package com.keevo.shared.event.catalog.product;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * ProductMergedEvent — Story 20.1: the owner merged {@code sourceProductId} into {@code targetProductId} (the source
 * is archived and redirected to the target, history untouched).
 *
 * <p>Consumed by {@link com.keevo.shared.infrastructure.web.AuditEventListener}: « Produit fusionné » with both names
 * (no technical value in the audit detail).
 */
public record ProductMergedEvent(
        UUID sourceProductId,
        String sourceProductName,
        UUID targetProductId,
        String targetProductName,
        String tenantId,
        UUID actorId,
        Instant occurredAt
) implements DomainEvent {}
