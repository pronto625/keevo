package com.keevo.messaging.notification.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * DraftPendingValidation — Tracks DRAFT products awaiting owner validation.
 *
 * <p>Persisted in {@code draft_notifications} table.
 * Row is marked {@code acknowledged = true} when {@code UpdateProductUseCase}
 * promotes a DRAFT → ACTIVE for the same productId.
 *
 * <p>Used by {@code GET /api/v1/products/drafts/count} to drive the in-app badge counter.
 */
public class DraftPendingValidation {

    private final UUID    id;
    private final UUID    productId;
    private final String  productName;
    private final UUID    actorId;     // employee who created the draft
    private final UUID    tenantId;
    private final Instant createdAt;
    private boolean       acknowledged; // set to true when owner promotes DRAFT → ACTIVE

    private DraftPendingValidation(UUID id, UUID productId, String productName,
                                   UUID actorId, UUID tenantId, Instant createdAt,
                                   boolean acknowledged) {
        this.id           = id;
        this.productId    = productId;
        this.productName  = productName;
        this.actorId      = actorId;
        this.tenantId     = tenantId;
        this.createdAt    = createdAt;
        this.acknowledged = acknowledged;
    }

    /** Factory method — creates with acknowledged = false (default). */
    public static DraftPendingValidation create(UUID productId, String productName,
                                                UUID actorId, UUID tenantId) {
        return new DraftPendingValidation(UUID.randomUUID(), productId, productName,
                actorId, tenantId, Instant.now(), false);
    }

    /** Reconstruct from persistence. */
    public static DraftPendingValidation of(UUID id, UUID productId, String productName,
                                            UUID actorId, UUID tenantId,
                                            Instant createdAt, boolean acknowledged) {
        return new DraftPendingValidation(id, productId, productName,
                actorId, tenantId, createdAt, acknowledged);
    }

    public void acknowledge() { this.acknowledged = true; }

    public UUID    getId()           { return id; }
    public UUID    getProductId()    { return productId; }
    public String  getProductName()  { return productName; }
    public UUID    getActorId()      { return actorId; }
    public UUID    getTenantId()     { return tenantId; }
    public Instant getCreatedAt()    { return createdAt; }
    public boolean isAcknowledged()  { return acknowledged; }
}
