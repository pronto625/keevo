package com.keevo.messaging.notification.adapter.out.persistence;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * DraftNotificationJpaEntity — JPA mapping for {@code draft_notifications} table.
 *
 * <p>Per-tenant table (schema-per-tenant routing via {@code search_path}).
 * Story 2.4.
 */
@Entity
@Table(name = "draft_notifications")
public class DraftNotificationJpaEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "acknowledged", nullable = false)
    private boolean acknowledged;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected DraftNotificationJpaEntity() {}

    public DraftNotificationJpaEntity(UUID id, UUID productId, String productName,
                                       UUID actorId, UUID tenantId,
                                       boolean acknowledged, Instant createdAt) {
        this.id           = id;
        this.productId    = productId;
        this.productName  = productName;
        this.actorId      = actorId;
        this.tenantId     = tenantId;
        this.acknowledged = acknowledged;
        this.createdAt    = createdAt;
    }

    public UUID    getId()          { return id; }
    public UUID    getProductId()   { return productId; }
    public String  getProductName() { return productName; }
    public UUID    getActorId()     { return actorId; }
    public UUID    getTenantId()    { return tenantId; }
    public boolean isAcknowledged() { return acknowledged; }
    public Instant getCreatedAt()   { return createdAt; }

    public void setAcknowledged(boolean acknowledged) { this.acknowledged = acknowledged; }
}
