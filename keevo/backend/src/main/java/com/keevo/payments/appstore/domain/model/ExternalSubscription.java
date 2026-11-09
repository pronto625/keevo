package com.keevo.payments.appstore.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Story 21.2 — an App Store subscription bound to one company ({@code public.external_subscriptions}). Mutable state
 * is replaced through the {@code with…} copies and written back in one UPDATE.
 *
 * @param grantedExpiresAt the plan expiry this subscription last granted (a revocation only undoes its own grant)
 * @param lastEventMs      timestamp of the last state event applied (older events are stale)
 */
public record ExternalSubscription(UUID id, UUID tenantId, String tenantSchema, UUID appUserId,
                                   String originalTransactionId, String productId, String planCode, String cycleCode,
                                   SubscriptionStatus status, boolean autoRenew, boolean sandbox, Instant expiresAt,
                                   Instant graceExpiresAt, Instant grantedExpiresAt, long lastEventMs) {

    public ExternalSubscription withState(SubscriptionStatus newStatus, boolean newAutoRenew, Instant newExpiresAt,
                                          Instant newGraceExpiresAt) {
        return new ExternalSubscription(id, tenantId, tenantSchema, appUserId, originalTransactionId, productId,
                planCode, cycleCode, newStatus, newAutoRenew, sandbox, newExpiresAt, newGraceExpiresAt,
                grantedExpiresAt, lastEventMs);
    }

    public ExternalSubscription withProduct(String newProductId, String newPlanCode, String newCycleCode) {
        return new ExternalSubscription(id, tenantId, tenantSchema, appUserId, originalTransactionId, newProductId,
                newPlanCode, newCycleCode, status, autoRenew, sandbox, expiresAt, graceExpiresAt, grantedExpiresAt,
                lastEventMs);
    }

    public ExternalSubscription withGranted(Instant granted) {
        return new ExternalSubscription(id, tenantId, tenantSchema, appUserId, originalTransactionId, productId,
                planCode, cycleCode, status, autoRenew, sandbox, expiresAt, graceExpiresAt, granted, lastEventMs);
    }

    public ExternalSubscription withLastEvent(long eventMs) {
        return new ExternalSubscription(id, tenantId, tenantSchema, appUserId, originalTransactionId, productId,
                planCode, cycleCode, status, autoRenew, sandbox, expiresAt, graceExpiresAt, grantedExpiresAt,
                Math.max(lastEventMs, eventMs));
    }

    /** Bound to another company (TRANSFER only) — the grant belongs to the old company, so it is cleared. */
    public ExternalSubscription withOwner(UUID newTenantId, String newSchema, UUID newAppUserId) {
        return new ExternalSubscription(id, newTenantId, newSchema, newAppUserId, originalTransactionId, productId,
                planCode, cycleCode, status, autoRenew, sandbox, expiresAt, graceExpiresAt, null, lastEventMs);
    }

    public ExternalSubscription withSandbox(boolean newSandbox) {
        return new ExternalSubscription(id, tenantId, tenantSchema, appUserId, originalTransactionId, productId,
                planCode, cycleCode, status, autoRenew, newSandbox, expiresAt, graceExpiresAt, grantedExpiresAt,
                lastEventMs);
    }
}
