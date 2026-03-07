package com.keevo.subscription.plan.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * SubscriptionJpaEntity — JPA mapping for the per-tenant {@code subscriptions} table.
 *
 * <p>Infrastructure layer only — NEVER used in domain or application layer.
 * The table schema is defined in {@code TenantSchemaProvisioner.DDL_SUBSCRIPTIONS}
 * and provisioned programmatically for each tenant on registration.
 *
 * <p>Note: This entity does NOT extend {@code JpaBaseEntity} because the DDL
 * does not include an {@code updated_at} column. All @PrePersist/@PreUpdate hooks
 * are skipped intentionally — subscription rows are updated only by admin commands
 * and the expiry scheduler.
 *
 * <p>Domain model: {@link com.keevo.subscription.plan.domain.model.Subscription}.
 */
@Entity
@Table(name = "subscriptions")
public class SubscriptionJpaEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "plan_type", nullable = false, length = 20)
    private String planType;

    @Column(name = "max_stores", nullable = false)
    private int maxStores;

    @Column(name = "max_products", nullable = false)
    private int maxProducts;

    @Column(name = "max_employees", nullable = false)
    private int maxEmployees;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected SubscriptionJpaEntity() {}

    public SubscriptionJpaEntity(UUID id, String planType, int maxStores, int maxProducts,
                                  int maxEmployees, String status, Instant createdAt,
                                  Instant expiresAt) {
        this.id           = id;
        this.planType     = planType;
        this.maxStores    = maxStores;
        this.maxProducts  = maxProducts;
        this.maxEmployees = maxEmployees;
        this.status       = status;
        this.createdAt    = createdAt;
        this.expiresAt    = expiresAt;
    }

    public UUID    getId()           { return id; }
    public String  getPlanType()     { return planType; }
    public int     getMaxStores()    { return maxStores; }
    public int     getMaxProducts()  { return maxProducts; }
    public int     getMaxEmployees() { return maxEmployees; }
    public String  getStatus()       { return status; }
    public Instant getCreatedAt()    { return createdAt; }
    public Instant getExpiresAt()    { return expiresAt; }

    public void setPlanType(String planType)     { this.planType = planType; }
    public void setStatus(String status)         { this.status = status; }
    public void setExpiresAt(Instant expiresAt)  { this.expiresAt = expiresAt; }
    public void setMaxStores(int maxStores)       { this.maxStores = maxStores; }
    public void setMaxProducts(int maxProducts)   { this.maxProducts = maxProducts; }
    public void setMaxEmployees(int maxEmployees) { this.maxEmployees = maxEmployees; }
}
