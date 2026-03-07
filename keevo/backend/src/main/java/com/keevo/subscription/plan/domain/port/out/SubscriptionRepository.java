package com.keevo.subscription.plan.domain.port.out;

import com.keevo.subscription.plan.domain.model.Subscription;

import java.util.Optional;

/**
 * SubscriptionRepository — Output port for subscription persistence.
 *
 * <p>Reads/writes the single {@code subscriptions} row in the current tenant schema
 * (schema-per-tenant, already provisioned by {@code TenantSchemaProvisioner}).
 */
public interface SubscriptionRepository {

    /**
     * Find the current tenant's active subscription (the first row by creation date).
     * Returns empty if no row exists (should not happen in normal operation).
     */
    Optional<Subscription> findActivePlan();

    /**
     * Persist changes to the subscription (plan type downgrade, status update, etc.).
     *
     * @return the updated subscription
     */
    Subscription save(Subscription subscription);
}
