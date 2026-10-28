package com.keevo.crm.contact.domain.event;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * SupplierUpdatedEvent — published when a supplier's name, phone or e-mail changes.
 * {@code before} / {@code after} hold only the changed fields. Consumed by
 * AuditEventListener (SUPPLIER_UPDATED).
 */
public record SupplierUpdatedEvent(
        UUID supplierId,
        String supplierName,
        Map<String, Object> before,
        Map<String, Object> after,
        UUID actorId,
        String tenantId,
        Instant occurredAt
) {}
