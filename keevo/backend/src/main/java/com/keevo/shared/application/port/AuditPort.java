package com.keevo.shared.application.port;

import java.util.UUID;

/**
 * AuditPort — Domain port interface for recording audit trail entries.
 *
 * <p>Pure Java — NO Spring/JPA imports.
 * Implementation lives in the adapter layer (adapter/out/persistence/).
 */
public interface AuditPort {

    /**
     * Record an immutable audit event.
     *
     * @param actorId    UUID of the user performing the action
     * @param tenantId   Tenant context identifier
     * @param action     Action code e.g. "PRODUCT_CREATED", "SALE_CANCELLED"
     * @param entityType Affected entity type e.g. "Product", "Sale"
     * @param entityId   UUID of the affected entity
     * @param details    JSON-serialisable details (nullable)
     */
    void record(
            UUID actorId,
            String tenantId,
            String action,
            String entityType,
            UUID entityId,
            String details
    );
}
