package com.keevo.catalog.product.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * ProductImportedFromCsvEvent — Published for each product successfully imported via CSV.
 * Used by AuditEventListener to record an audit log entry per product.
 */
public record ProductImportedFromCsvEvent(
        UUID    productId,
        String  productName,
        UUID    actorId,
        String  tenantId,
        int     csvLineNumber,
        Instant occurredAt
) {}
