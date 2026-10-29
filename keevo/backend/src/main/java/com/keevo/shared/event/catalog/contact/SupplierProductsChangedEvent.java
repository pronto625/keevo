package com.keevo.shared.event.catalog.contact;

import com.keevo.shared.event.catalog.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * SupplierProductsChangedEvent — published when the products supplied by a supplier
 * ({@code product_suppliers}) change. Counts only: product ids are never shown in the
 * action history. Consumed by AuditEventListener (SUPPLIER_PRODUCTS_UPDATED).
 */
public record SupplierProductsChangedEvent(
        UUID supplierId,
        String supplierName,
        int previousCount,
        int newCount,
        int added,
        int removed,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) implements DomainEvent {}
