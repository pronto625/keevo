package com.keevo.subscription.plan.domain.port.in;

/**
 * DowngradeExpiredTrialsUseCase — Input port: scheduled service that auto-downgrades
 * expired PREMIUM_TRIAL and PREMIUM subscriptions to FREE.
 *
 * <p>Triggered daily at 02:00 by {@code SubscriptionExpiryScheduler}.
 */
public interface DowngradeExpiredTrialsUseCase {
    /**
     * Find all tenant subscriptions where planType IN (PREMIUM_TRIAL, PREMIUM)
     * AND status=ACTIVE AND expires_at &lt; NOW() — then downgrade planType to FREE.
     *
     * @return number of tenants downgraded
     */
    int execute(DowngradeExpiredTrialsCommand command);
}
