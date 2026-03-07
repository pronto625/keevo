package com.keevo.subscription.plan.domain.model;

import com.keevo.identity.auth.domain.model.PlanType;

import java.time.Instant;
import java.util.UUID;

/**
 * Subscription — Immutable domain model representing a tenant's subscription record.
 *
 * <p>Pure Java — no framework imports. One row per tenant (single subscription
 * at any given time). Created by {@code TenantSchemaProvisioner} during registration
 * with {@code planType=PREMIUM_TRIAL}.
 *
 * <p>GoF Pattern: <b>State</b> — subscription lifecycle:
 * PREMIUM_TRIAL → FREE (on expiry), FREE → PREMIUM (on payment), PREMIUM → FREE (on non-renewal).
 */
public final class Subscription {

    private final UUID id;
    private final PlanType planType;
    private final SubscriptionStatus status;
    private final int maxStores;
    private final int maxProducts;
    private final int maxEmployees;
    private final Instant createdAt;
    private final Instant expiresAt;  // null for FREE (no expiry date)

    public Subscription(UUID id,
                        PlanType planType,
                        SubscriptionStatus status,
                        int maxStores,
                        int maxProducts,
                        int maxEmployees,
                        Instant createdAt,
                        Instant expiresAt) {
        this.id = id;
        this.planType = planType;
        this.status = status;
        this.maxStores = maxStores;
        this.maxProducts = maxProducts;
        this.maxEmployees = maxEmployees;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public UUID getId()                 { return id; }
    public PlanType getPlanType()       { return planType; }
    public SubscriptionStatus getStatus() { return status; }
    public int getMaxStores()           { return maxStores; }
    public int getMaxProducts()         { return maxProducts; }
    public int getMaxEmployees()        { return maxEmployees; }
    public Instant getCreatedAt()       { return createdAt; }
    public Instant getExpiresAt()       { return expiresAt; }

    // ── Helper predicates ─────────────────────────────────────────────────────

    public boolean isSuspended() { return status == SubscriptionStatus.SUSPENDED; }
    public boolean isActive()    { return status == SubscriptionStatus.ACTIVE; }
    public boolean isPremiumTrial() { return planType == PlanType.PREMIUM_TRIAL; }
    public boolean isPremium()   { return planType == PlanType.PREMIUM; }
    public boolean isFree()      { return planType == PlanType.FREE; }
}
